package com.mawai.wiibagent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.learning.ReviewMaterialAssembler;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.ResilientChatService;
import com.mawai.wiibagent.llm.ToolCallTraceHook;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * 唤醒回路核心：一次唤醒 = 一个无状态 ReactLoop 会话（BYOK 模型 + 数据工具 + 绑定子账户的交易工具），
 * 决策全文 + 动作轨迹 + 权益落 ai_trader_decision。
 * 失败语义：连续 5 次失败自动 PAUSED（key 无效是永久错误，不等连败当场停）；权益跌破初始 1% 判 LIQUIDATED 终局。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderWakeupRunner {

    /** 距下一边界不足此秒数=触发过晚（事件迟到），放弃本轮不算失败 */
    static final int MIN_WAKE_SECONDS = 30;
    /** 单轮预算上限：再慢的端点也不许无限吃时长 */
    static final int MAX_WAKE_SECONDS = 600;
    /** 截止安全余量：唤醒决不占用下一根K线 */
    private static final long DEADLINE_SAFETY_MS = 5_000;
    /**
     * 单次唤醒模型调用上限（ReAct 迭代保险丝，挡住无限工具循环烧用户的钱）。
     * <p>
     * 值给到 12 是因为并不并行差得远：会并行的模型一轮发 4~9 个 tool_call，2~3 次调用就取完数据；
     * 不并行的一轮一个，3 币多周期求证根本走不完。撞上限不算失败（见下面 setError 那段），
     * 但收束时最后一条是纯 tool_call、正文空，这轮决策就没有收尾的结论块——
     * 下一轮的检验旧论点和复盘素材都跟着缺。
     */
    static final int MAX_MODEL_CALLS = 12;
    static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** 出局判定线：权益 < 初始资金 10000 的 1% */
    static final BigDecimal BUST_OUT_FLOOR = new BigDecimal("100");
    private static final int RECENT_DECISIONS = 5;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    /** 休眠提示里的时刻按北京时间（与 WakeWindow.ZONE 同源）：时段是北京时间，下次时刻也得是，否则容器 TZ 非 +8 时两句会错位 */
    private static final DateTimeFormatter BJ_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(WakeWindow.ZONE);

    private final TraderModelFactory modelFactory;
    private final TraderPromptAssembler promptAssembler;
    private final SimTradeClient simTradeClient;
    private final BinanceRestClient binanceRestClient;
    private final IndicatorToolkit indicatorToolkit;
    private final MarketToolkit marketToolkit;
    private final NewsToolkit newsToolkit;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final TraderPlanStore planStore;
    private final UserLangResolver userLangResolver;
    private final PromptCatalog prompts;
    /** sim 拒因按错误码成文（见 SimTradeClient.describe），同样跟 trader 主人的语言 */
    private final MessageCatalog messages;
    private final LocalizedToolCallbacks localizedTools;
    /** stale 教材过滤的共用入口（与复盘时间线/chat 同一套识别逻辑） */
    private final ReviewMaterialAssembler materialAssembler;
    /** 财经日历注入块（过去12h已公布+未来24h即将公布）；null=无相关事件或取数失败，整块缺席 */
    private final EconCalendarAssembler econCalendar;
    /** 论点战绩统计块，进观察包；null=本局无可统计或取数失败，整块缺席 */
    private final PlayStatsAssembler playStats;
    /** 唤醒现场：过程逐帧推给竞技场订阅者，轨迹落 trace_json */
    private final TraderLiveHub hub;

    /** 墙钟注入点：预算计算要可测（测试里把"现在"钉在边界附近） */
    LongSupplier nowMs = System::currentTimeMillis;

    /** 唤醒预算(秒)：截止 = 下一边界前 5s——唤醒决不占用下一根K线；上限 600s。 */
    static long wakeBudgetSeconds(long boundary, long intervalMs, long now) {
        long deadline = boundary + intervalMs - DEADLINE_SAFETY_MS;
        return Math.min((deadline - now) / 1000, MAX_WAKE_SECONDS);
    }

    public void wake(AiTrader trader, long boundaryTime) {
        wake(trader, boundaryTime, boundaryTime, AiTraderDecision.KIND_TRADE);
    }

    /**
     * 手动唤醒（对话轨 wake_trader，已过 HITL）：回路与例行相同，决策行标 MANUAL——时间线要看得出扳机在人手里。
     * 预算仍按对齐边界算，wakeTime 是按下按钮那一刻——与警报同款，回注查询才带得上同一根K线上的例行决策
     */
    public void wakeManual(AiTrader trader, long boundaryTime) {
        wake(trader, boundaryTime, nowMs.getAsLong(), AiTraderDecision.KIND_MANUAL);
    }

    private void wake(AiTrader trader, long boundaryTime, long wakeTime, String kind) {
        long intervalMs = TraderScheduler.INTERVAL_MS.getOrDefault(trader.getIntervalCode(), 300_000L);
        long budgetSeconds = wakeBudgetSeconds(boundaryTime, intervalMs, nowMs.getAsLong());
        // 语言查一次用到底：系统提示、开场白、落库的 error 文案必须是同一门，混着来模型立刻跟着混
        AgentLang lang = userLangResolver.of(trader.getUserId());
        AiTraderDecision decision = baseDecision(trader, wakeTime);
        decision.setKind(kind);
        if (budgetSeconds < MIN_WAKE_SECONDS) {
            // 事件迟到太多：与其用残余时间仓促决策，不如放弃等下一根新鲜K线（不算失败不计连败）
            decision.setStatus(AiTraderDecision.STATUS_SKIPPED);
            decision.setError(prompts.get(lang, "trader.error.lateTrigger",
                    Map.of("seconds", MIN_WAKE_SECONDS)));
            decisionMapper.insert(decision);
            log.warn("[Trader] 触发过晚放弃 traderId={} boundary={} budget={}s", trader.getId(), boundaryTime, budgetSeconds);
            return;
        }
        doWake(trader, wakeTime, budgetSeconds, decision, null, lang);
    }

    /**
     * 波动哨兵警报唤醒：kind=ALERT、wake_time=触发时刻（非K线边界）；
     * 预算截止仍是下一例行边界−5s——警报绝不占用下一根K线。
     */
    public void wakeAlert(AiTrader trader, AlertTrigger trigger) {
        long intervalMs = TraderScheduler.INTERVAL_MS.getOrDefault(trader.getIntervalCode(), 3_600_000L);
        long now = nowMs.getAsLong();
        long boundary = now - Math.floorMod(now, intervalMs);
        long budgetSeconds = wakeBudgetSeconds(boundary, intervalMs, now);
        if (budgetSeconds < MIN_WAKE_SECONDS) {
            // 调度器已预检，这里兜底：例行将至警报静默放弃，不写决策行（SKIPPED 只属于例行调度）
            log.info("[Trader] 例行将至警报放弃 traderId={} {}", trader.getId(), trigger.symbol());
            return;
        }
        AiTraderDecision decision = baseDecision(trader, trigger.triggeredAt());
        decision.setKind(AiTraderDecision.KIND_ALERT);
        doWake(trader, trigger.triggeredAt(), budgetSeconds, decision, trigger,
                userLangResolver.of(trader.getUserId()));
    }

    private void doWake(AiTrader trader, long boundaryTime, long budgetSeconds,
                        AiTraderDecision decision, AlertTrigger trigger, AgentLang lang) {
        long start = System.currentTimeMillis();
        // begin 之前出的异常没有这一轮现场
        TraderLiveHub.Run run = null;
        try {
            List<FuturesPositionDTO> positions = simTradeClient.getAllPositions(trader.getSimUserId());
            BigDecimal equity = computeEquity(trader.getSimUserId(), positions);
            decision.setEquity(equity);
            if (equity.compareTo(BUST_OUT_FLOOR) < 0) {
                markBustOut(trader, decision, lang);
                return;
            }

            List<FuturesOrderResponse> pendingOrders = simTradeClient.getPendingOrders(trader.getSimUserId(), null);
            run = hub.begin(trader, decision.getKind(), decision.getWakeTime(), budgetSeconds, equity, positions.size(), pendingOrders.size());
            String reasoning = runAgentSession(trader, boundaryTime, budgetSeconds, positions, pendingOrders, equity, decision, trigger, lang, run);
            decision.setStatus(AiTraderDecision.STATUS_OK);
            decision.setReasoning(reasoning);
            try {
                decision.setEquity(computeEquity(trader.getSimUserId(), simTradeClient.getAllPositions(trader.getSimUserId())));
            } catch (Exception e) {
                log.warn("[Trader] 动作后权益刷新失败，沿用唤醒前快照 traderId={} equity={} msg={}",
                        trader.getId(), equity, e.getMessage());
            }
            decision.setLatencyMs((int) (System.currentTimeMillis() - start));
            decision.setTraceJson(run.finish(AiTraderDecision.STATUS_OK, null, decision.getEquity(),
                    decision.getLatencyMs(), decision.getModelCalls(), decision.getTotalTokens()));
            decisionMapper.insert(decision);
            run.end(decision.getId()); // insert后发run_end，目的是为了能够被查询到
            clearFailures(trader);
        } catch (Exception e) {
            boolean keyInvalid = false;
            String msg;
            if (e instanceof TimeoutException) {
                msg = prompts.get(lang, "trader.error.wakeTimeout", Map.of("seconds", budgetSeconds));
            } else if (e instanceof SimTradeClient.SimBizException) {
                msg = SimTradeClient.describe(e, messages, lang);
            } else {
                keyInvalid = LlmErrorMessages.unauthorized(e);
                msg = LlmErrorMessages.classify(e, prompts, lang);
            }
            log.warn("[Trader] 唤醒失败 traderId={} boundary={} msg={}", trader.getId(), boundaryTime, msg, e);
            if (decision.getId() != null) {
                return;
            }
            decision.setStatus(AiTraderDecision.STATUS_ERROR);
            decision.setError(msg.length() > 500 ? msg.substring(0, 500) : msg);
            decision.setLatencyMs((int) (System.currentTimeMillis() - start));
            if (run != null) {
                decision.setTraceJson(run.finish(AiTraderDecision.STATUS_ERROR, decision.getError(), decision.getEquity(),
                        decision.getLatencyMs(), decision.getModelCalls(), decision.getTotalTokens()));
            }
            decisionMapper.insert(decision);
            if (run != null) {
                run.end(decision.getId());
            }
            recordFailure(trader, msg, lang, keyInvalid);
        }
    }

    /** 上一唤醒未完被跳过：留痕，竞技场时间线可见调度诚实。 */
    public void recordSkipped(AiTrader trader, long boundaryTime) {
        AiTraderDecision d = baseDecision(trader, boundaryTime);
        d.setStatus(AiTraderDecision.STATUS_SKIPPED);
        d.setError(prompts.get(userLangResolver.of(trader.getUserId()), "trader.error.skipped"));
        decisionMapper.insert(d);
    }

    /**
     * ReactLoop 会话：备料（工具/计划/最近几轮/提示词）→ 建循环 → 开场白 → 限时执行，过程逐帧推给现场 {@code run}。
     * 返回模型最终文本；动作轨迹随 decision 一并写入。trigger 非空=警报唤醒（只换开场白）。
     */
    private String runAgentSession(AiTrader trader, long boundaryTime, long budgetSeconds,
                                   List<FuturesPositionDTO> positions, List<FuturesOrderResponse> pendingOrders,
                                   BigDecimal equity, AiTraderDecision decision, AlertTrigger trigger,
                                   AgentLang lang, TraderLiveHub.Run run) throws Exception {
        // 用量统计装饰器 每轮新建
        UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
        Set<String> whitelist = Arrays.stream(trader.getSymbols().split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        TradeTools tradeTools = tradeToolsFor(trader, whitelist, boundaryTime, budgetSeconds, lang);
        // 计划对照 sim 持仓/挂单：已了结的归档、限价成交的补上仓位 id，存活的随持仓注入账户状态
        TraderPlanStore.Rebind rebind = rebindPlans(trader, positions, pendingOrders, boundaryTime);
        // 获取最近的唤醒轮，并过滤掉stale内容
        List<RecentWake> recent = recentWakes(trader, boundaryTime);
        // system 提示词：平台模板 + 复盘/学习笔记 + 自定义指令 + 固定收尾格式 + 输出语言
        String prompt = promptAssembler.assemble(trader, lang);
        // 观察包：自己的状态事实，进开场白。上次醒来后的事件 + 账户（持仓/挂单带计划）+ 上一轮结论 + 最近轨迹 + 论点战绩
        String observation = observation(trader, equity, positions, pendingOrders, rebind, recent, boundaryTime, lang);
        // 留言进开场白不进 system；取一次就消费一轮，例行/警报两种开场白共用这一份
        String ownerNote = promptAssembler.ownerNoteBlock(trader, lang);

        // 全量工具轨迹（含数据工具）：收集器在本方法手里，超时 cancel 也保得住已发生的记录
        ToolCallTraceHook trace = new ToolCallTraceHook();
        ResilientChatService chat = ResilientChatService.builder().model(model).systemPrompt(prompt)
                .tools(wakeTools(lang, tradeTools))
                // 首轮强制调工具：不看数据不许决策；弱模型不支持 tool_choice 会以 ERROR 落库并最终自动暂停
                .forceFirstToolChoice("required").build();
        ReactLoop loop = ReactLoop.builder().chat(chat)
                .streaming(true)      // 模型文本逐字推给现场
                .limiter(new ModelCallLimiter(MAX_MODEL_CALLS, prompts.get(lang, "llm.callLimit.notExecuted"),
                        prompts.get(lang, "llm.callLimit.lastCall")))
                .trace(trace).build();

        String calendar = econCalendar.assemble(nowMs.getAsLong(), lang);
        String instruction = trigger != null
                ? alertInstruction(trader, trigger, recent.isEmpty() ? null : recent.getFirst().wakeTime(), observation, calendar, lang, ownerNote)
                : routineInstruction(trader, boundaryTime, observation, marketSnapshot(whitelist, lang), calendar, lang, ownerNote);
        run.prompt(prompt, instruction);
        // 中断信号交给循环，finally 里 complete：在途模型流不再往下烧
        CompletableFuture<Void> cancel = new CompletableFuture<>();

        record SessionOutcome(String reasoning, int modelCalls) {
        }
        // 虚拟线程 + FutureTask 承载超时；超时后本轮作废（已发出的订单不回滚——sim 是事实源）
        FutureTask<SessionOutcome> task = new FutureTask<>(() -> {
            run.callStart();
            ReactLoop.Result result = loop.run(List.of(new UserMessage(instruction)), null, cancel, new ReactLoop.Listener() {
                @Override
                public void chunk(ChatResponse frame) {
                    String chunk = frame.getResults().getFirst().getOutput().getText();
                    if (chunk != null && !chunk.isEmpty()) {
                        run.token(chunk);
                    }
                }

                @Override
                public void message(Message m) {
                    // 不按来源分支：保险丝补的占位回执也走这条路
                    if (m instanceof AssistantMessage assistant) {
                        run.callEnd(assistant.getText(), assistant.getToolCalls());
                    } else if (m instanceof ToolResponseMessage responses) {
                        for (ToolResponseMessage.ToolResponse r : responses.getResponses()) {
                            run.toolResult(r.id(), r.name(), r.responseData());
                        }
                        run.callStart();
                    }
                }
            });
            return new SessionOutcome(finalReasoning(prompts, lang, result.messages()), result.modelCalls());
        });
        Thread.startVirtualThread(task);
        SessionOutcome outcome;
        try {
            outcome = task.get(budgetSeconds, TimeUnit.SECONDS);
        } finally {
            cancel.complete(null);
            task.cancel(true);
            // 无论成败，动作轨迹都要留：超时/异常时已执行的开平仓是真实发生的
            List<JSONObject> actions = mergeActions(trace.calls(), tradeTools.actions());
            decision.setActionsJson(JSON.toJSONString(actions));
            decision.setToolCalls(actions.size());
            // 用量同理落在 finally：超时作废的那一轮，token 也是真烧掉了，不能不记
            UsageTrackingChatModel.UsageSnapshot usage = model.snapshot();
            decision.setModelCalls(usage.modelCalls());
            decision.setPromptTokens(usage.promptTokens());
            decision.setCompletionTokens(usage.completionTokens());
            decision.setTotalTokens(usage.totalTokens());
        }
        if (outcome.modelCalls() >= MAX_MODEL_CALLS) {
            // 保险丝收束不算失败（已有动作真实生效），但必须留痕——否则时间线上像正常决策
            decision.setError(prompts.get(lang, "trader.error.callLimit", Map.of("limit", MAX_MODEL_CALLS)));
        }
        return outcome.reasoning();
    }

    /** 交易工具：绑定该 trader 的 sim 子账户、白名单与风险规格，每次唤醒 new 一个。权益按开仓时现查的持仓算 */
    private TradeTools tradeToolsFor(AiTrader trader, Set<String> whitelist, long boundaryTime, long budgetSeconds, AgentLang lang) {
        return new TradeTools(
                simTradeClient,
                trader.getSimUserId(),
                whitelist,
                positions -> computeEquity(trader.getSimUserId(), positions),
                sym -> JSON.parseObject(binanceRestClient.getPremiumIndex(sym)).getBigDecimal("markPrice"),
                planStore,
                new TradeTools.WakeCtx(trader.getId(), trader.getRoundNo(), boundaryTime, System.currentTimeMillis() + budgetSeconds * 1000, TraderRiskConfig.of(trader), lang),
                prompts,
                messages
        );
    }

    /**
     * 回注给下一轮看的一轮唤醒：决策行里只留提示词用得到的几列。
     * 被主人标记忽略的交易，内容在这里已经剔掉（正文剔那个币的段、动作名剔那笔的），
     * 行头的时刻/状态/权益原样——那是唤醒事实，不是教材。
     */
    record RecentWake(long wakeTime, String status, BigDecimal equity, String error,
                      String reasoning, List<String> tools) {
    }

    /**
     * 最近 {@value #RECENT_DECISIONS} 条交易行（例行/警报/手动），最新在前。
     * 复盘/学习行不进来：它们的产出已经走 memory/learning_notes 注入，再进就是重复占字数。
     * 忽略过滤要按计划生命期判段落归属（同币另一方向没被忽略的段得留），计划查本局全部；一条决策都没有就不查。
     */
    private List<RecentWake> recentWakes(AiTrader trader, long boundaryTime) {
        List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.KIND_TRADE, AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL)
                .lt(AiTraderDecision::getWakeTime, boundaryTime)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT " + RECENT_DECISIONS));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<AiTraderPlan> plans = planStore.listAll(trader.getId(), trader.getRoundNo());
        List<RecentWake> out = new ArrayList<>(rows.size());
        for (AiTraderDecision d : rows) {
            String reasoning = materialAssembler.staleFiltered(d, plans);
            out.add(new RecentWake(d.getWakeTime(), d.getStatus(), d.getEquity(), d.getError(),
                    reasoning == null ? "" : reasoning, materialAssembler.staleFilteredToolNames(d, plans)));
        }
        return out;
    }

    /**
     * 把 sim 的持仓键和开仓挂单键、持仓的仓位 id 交给 {@link TraderPlanStore#rebind}：
     * 还活着的计划留下并补上仓位 id，已了结的归档。存活的进账户状态，归档/成交的进事件块
     */
    private TraderPlanStore.Rebind rebindPlans(AiTrader trader, List<FuturesPositionDTO> positions,
                                               List<FuturesOrderResponse> pendingOrders, long boundaryTime) {
        Set<String> liveKeys = new HashSet<>();
        Map<String, Long> positionIdByKey = new HashMap<>();
        positions.forEach(p -> {
            liveKeys.add(TraderPlanStore.key(p.getSymbol(), p.getSide()));
            positionIdByKey.put(TraderPlanStore.key(p.getSymbol(), p.getSide()), p.getId());
        });
        for (FuturesOrderResponse o : pendingOrders) {
            if (o.getOrderSide() != null && o.getOrderSide().startsWith("OPEN_")) {
                liveKeys.add(TraderPlanStore.key(o.getSymbol(), o.getOrderSide().substring("OPEN_".length())));
            }
        }
        return planStore.rebind(trader.getId(), trader.getRoundNo(), liveKeys, positionIdByKey, boundaryTime);
    }

    /** 观察包：事件 + 账户 + 上一轮结论 + 最近轨迹 + 论点战绩。例行/警报开场白共用，排在头部事实之后、问题之前 */
    String observation(AiTrader trader, BigDecimal equity, List<FuturesPositionDTO> positions,
                       List<FuturesOrderResponse> pendingOrders, TraderPlanStore.Rebind rebind,
                       List<RecentWake> recent, long boundaryTime, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        sb.append(events(trader, rebind, lang));
        sb.append('\n').append(prompts.get(lang, "trader.wake.accountHeader")).append('\n')
                .append(accountStateJson(prompts, lang, equity, positions, pendingOrders, rebind.live(), boundaryTime)).append('\n');
        sb.append('\n').append(lastConclusion(recent, lang));
        String trajectory = trajectory(recent, lang);
        if (!trajectory.isEmpty()) {
            sb.append('\n').append(trajectory);
        }
        String stats = playStats.assemble(trader, lang);
        if (stats != null) {
            sb.append('\n').append(stats);
        }
        return sb.toString();
    }

    /** 自上次唤醒以来：归档的计划配 sim 已平仓位说结局，限价成交的说成交；没事件返回空串 */
    private String events(AiTrader trader, TraderPlanStore.Rebind rebind, AgentLang lang) {
        if (rebind.closed().isEmpty() && rebind.filled().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n").append(prompts.get(lang, "trader.wake.eventsHeader")).append('\n');
        if (!rebind.closed().isEmpty()) {
            Map<Long, FuturesPositionDTO> closedById = new HashMap<>();
            simTradeClient.getClosedPositions(trader.getSimUserId(), PlayStatsAssembler.FETCH_LIMIT)
                    .forEach(p -> closedById.put(p.getId(), p));
            for (AiTraderPlan p : rebind.closed()) {
                // 没仓位 id = 限价单从没成交过（撤了或到期）
                FuturesPositionDTO pos = p.getPositionId() == null ? null : closedById.get(p.getPositionId());
                sb.append(pos == null
                        ? prompts.get(lang, "trader.wake.eventCancelled", Map.of("symbol", p.getSymbol(), "side", p.getSide()))
                        : prompts.get(lang, "trader.wake.eventClosed", Map.of(
                                "symbol", p.getSymbol(), "side", p.getSide(),
                                "manner", ReviewMaterialAssembler.closeManner(prompts, pos, lang),
                                "price", String.valueOf(pos.getClosedPrice()),
                                "pnl", money(pos.getClosedPnl()),
                                "playType", String.valueOf(p.getPlayType()),
                                "invalidation", p.getInvalidationCondition())))
                        .append('\n');
            }
        }
        for (AiTraderPlan p : rebind.filled()) {
            sb.append(prompts.get(lang, "trader.wake.eventFilled", Map.of("symbol", p.getSymbol(), "side", p.getSide()))).append('\n');
        }
        return sb.toString();
    }

    /** sim 的 closedPnl 可以为空（PlayStatsAssembler 同样按空处理），Map.of 不收 null */
    private static String money(BigDecimal v) {
        return v == null ? "?" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 最近一条写出了结论块的 OK 轮，结论块原样全文；一条都没有就说没有 */
    private String lastConclusion(List<RecentWake> recent, AgentLang lang) {
        for (RecentWake w : recent) {
            if (!AiTraderDecision.STATUS_OK.equals(w.status())) {
                continue;
            }
            String block = materialAssembler.conclusionBlock(w.reasoning());
            if (block == null) {
                continue;
            }
            return prompts.get(lang, "trader.wake.lastConclusionHeader", Map.of(
                    "time", TIME_FMT.format(Instant.ofEpochMilli(w.wakeTime())),
                    "equity", w.equity().setScale(0, RoundingMode.HALF_UP))) + "\n" + block + "\n";
        }
        return prompts.get(lang, "trader.wake.lastConclusionNone") + "\n";
    }

    /** 一行一轮：时刻 [状态] 权益，OK 行列工具名，非 OK 行列原因；SKIPPED 行没权益、OK 行多半没 error */
    private String trajectory(List<RecentWake> recent, AgentLang lang) {
        if (recent.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.wake.trajectoryHeader")).append('\n');
        for (RecentWake w : recent) {
            sb.append("- ").append(TIME_FMT.format(Instant.ofEpochMilli(w.wakeTime())))
                    .append(" [").append(w.status()).append(']');
            if (w.equity() != null) {
                sb.append(' ').append(prompts.get(lang, "trader.label.equity",
                        Map.of("value", w.equity().setScale(0, RoundingMode.HALF_UP))));
            }
            if (w.error() != null && !w.error().isBlank()) {
                sb.append(' ').append(w.error());
            }
            if (!w.tools().isEmpty()) {
                sb.append(' ').append(prompts.get(lang, "trader.wake.trajectoryTools", Map.of("tools", countNames(w.tools()))));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** klines, kline_structure×2 这种写法 */
    private static String countNames(List<String> names) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        names.forEach(n -> counts.merge(n, 1, Integer::sum));
        return counts.entrySet().stream()
                .map(e -> e.getValue() == 1 ? e.getKey() : e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    /**
     * 唤醒挂的整套工具。工具描述按语言取自词表 {@code tool.<工具名>}（词表没这条就用注解原描述）。
     * <p>
     * 包私有：单测直接调它，验的才是建循环时真正挂上去的那批工具，而不是测试里另抄一份清单。
     * <p>
     * 快讯工具的语言在这里烤进实例（英文取译文，缺译文回落中文原文）——取哪门语言的新闻不是模型的选择。
     */
    List<ToolCallback> wakeTools(AgentLang lang, TradeTools tradeTools) {
        return localizedTools.of(lang, tradeTools, indicatorToolkit, marketToolkit, newsToolkit.boundTo(lang));
    }

    /**
     * 决策正文：往前找最近一条有正文的助手消息，而不是死盯最后一条。
     * 保险丝在工具边收束时，末尾是纯 tool_call 的助手消息 + 未执行占位回执，正文都是空的——
     * 死盯最后一条就会写出 status=OK 却一个字没有的决策行，时间线上与正常决策无从区分。
     */
    private static String finalReasoning(PromptCatalog prompts, AgentLang lang, List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistant
                    && assistant.getText() != null && !assistant.getText().isBlank()) {
                return assistant.getText();
            }
        }
        return prompts.get(lang, "trader.wake.noReasoning");
    }

    /**
     * 例行唤醒开场白：单问题框架 + 行情快照锚定价格水平。
     * <p>
     * 收尾标记取 {@code trader.mark.conclusion}，与系统提示词同一条 key——两处必须同源。
     * <p>
     * 包私有非 private：标记同源那条钉子（{@code WakeInstructionI18nTest}）要拿它的成文比对。
     *
     * @param observation 观察包（{@link #observation}），空串=不带。紧跟头部事实，排在快照之前
     * @param calendar  财经日历块（{@link EconCalendarAssembler}），null=整块缺席。
     *                  与快照同属事实区，排在快照之后、休眠提示与单问题框架之前
     * @param ownerNote 主人留言段（{@link TraderPromptAssembler#ownerNoteBlock}），空串=无待读留言。
     *                  压在整段开场白最末：user 消息末尾是最近因位置，留言在这儿才是"本轮要回答的问题之一"
     */
    String routineInstruction(AiTrader trader, long wakeTime, String observation, String snapshot,
                              String calendar, AgentLang lang, String ownerNote) {
        long intervalMs = TraderScheduler.INTERVAL_MS.getOrDefault(trader.getIntervalCode(), 300_000L);
        // 手动唤醒的 wakeTime 是按下按钮那一刻，"已收盘的那根"和休眠提示要按对齐边界说；例行轮本来就对齐
        long boundaryTime = wakeTime - Math.floorMod(wakeTime, intervalMs);
        return prompts.get(lang, "trader.wake.routineHeader", Map.of(
                "interval", trader.getIntervalCode(),
                "time", TIME_FMT.format(Instant.ofEpochMilli(boundaryTime))))
                + observation
                + (snapshot.isEmpty() ? ""
                        : "\n" + prompts.get(lang, "trader.wake.snapshotHeader") + "\n" + snapshot)
                + (calendar == null ? "" : "\n" + calendar)
                // 休眠提示放快照之后（事实区）、单问题框架之前：不让"要睡了"成为模型读到的第一件事
                + sleepNotice(prompts, lang, WakeWindow.of(trader), boundaryTime, intervalMs, nowMs.getAsLong())
                + prompts.get(lang, "trader.wake.routineQuestion",
                        Map.of("mark", prompts.get(lang, "trader.mark.conclusion")))
                + ownerNote;
    }

    /**
     * 时段内末次唤醒的休眠提示：只给事实（例行唤醒到哪根为止、下次何时醒、期间止损止盈照常），并明说休眠本身
     * 不是任何方向动作的理由——"13 小时看不见"既诱导睡前减仓/收紧止损，也诱导"赶在休眠前多开一笔"，
     * 与警报开场白治的是同一种病（被事件驱动的非计划动作），措辞红线同一条。
     * 措辞不说"本轮是末次"：手动唤醒也走这条开场白（boundary 是当前边界），"到 X 那根为止"对两种轮次都成立；
     * 小时数从 now 算，手动轮晚于边界几十分钟也不会说错。不是末次或全天 → 空串。
     */
    static String sleepNotice(PromptCatalog prompts, AgentLang lang, WakeWindow window,
                              long boundaryTime, long intervalMs, long now) {
        if (window == null || !window.isLastBoundary(boundaryTime, intervalMs)) {
            return "";
        }
        // 末次唤醒⇒下一天同一时刻仍在时段内，一天之内必有下一根，不会 -1
        long next = window.nextBoundaryFrom(boundaryTime + intervalMs, intervalMs);
        double hours = (next - now) / 3_600_000.0;
        return "\n" + prompts.get(lang, "trader.wake.sleepNotice", Map.of(
                "window", window.text(),
                "lastBar", BJ_FMT.format(Instant.ofEpochMilli(boundaryTime)),
                "next", BJ_FMT.format(Instant.ofEpochMilli(next)),
                "hours", String.format(Locale.ROOT, "%.1f", hours))) + "\n";
    }

    /**
     * 警报唤醒开场白：事实全代码注入（振幅/方向/上次唤醒时间/距例行还有多久），
     * 反锚定是灵魂——被波动惊醒正是恐慌平仓的高发场景，必须明说"未收盘不作数、
     * 止损在岗、不因被叫醒而必须动作"。
     *
     * @param lastWakeTime 本局上一次交易唤醒的时刻(ms)，null=本局还没醒过
     * @param observation 观察包，空串=不带；紧跟"上次唤醒"那行，排在日历之前
     * @param calendar  财经日历块，null=整块缺席——被波动惊醒时"刚才是否有数据公布/讲话"正是归因的关键事实
     * @param ownerNote 主人留言段，空串=无待读留言；位置同例行开场白，压在最末
     */
    String alertInstruction(AiTrader trader, AlertTrigger trig, Long lastWakeTime,
                            String observation, String calendar, AgentLang lang, String ownerNote) {
        long intervalMs = TraderScheduler.INTERVAL_MS.getOrDefault(trader.getIntervalCode(), 3_600_000L);
        long toNextMin = Math.max(1, (intervalMs - Math.floorMod(trig.triggeredAt(), intervalMs)) / 60_000);
        String lastWake = lastWakeTime == null ? prompts.get(lang, "trader.wake.alertNoWake")
                : prompts.get(lang, "trader.wake.alertWakeAt", Map.of(
                        "time", TIME_FMT.format(Instant.ofEpochMilli(lastWakeTime)),
                        "minutes", Math.max(1, (trig.triggeredAt() - lastWakeTime) / 60_000)));
        return prompts.get(lang, "trader.wake.alertHeader", Map.of(
                "symbol", trig.symbol(),
                "amplitude", trig.amplitudePct().stripTrailingZeros().toPlainString(),
                "direction", prompts.get(lang,
                        "trader.wake.direction." + trig.direction().toLowerCase(Locale.ROOT)),
                "price", trig.price().stripTrailingZeros().toPlainString())) + "\n"
                + prompts.get(lang, "trader.wake.alertLastWake",
                        Map.of("lastWake", lastWake, "minutes", toNextMin)) + "\n"
                + observation
                + (calendar == null ? "" : calendar)
                + prompts.get(lang, "trader.wake.alertNotice",
                        Map.of("interval", trader.getIntervalCode())) + "\n"
                + prompts.get(lang, "trader.wake.alertQuestion",
                        Map.of("mark", prompts.get(lang, "trader.mark.conclusion")))
                + ownerNote;
    }

    /**
     * 开场行情快照：各币标记价+资金费率，一行一个——把价格水平先钉进模型的世界，
     * 省下"查户口"的工具轮次；细节与多周期确认仍由模型自己用工具求证。
     * 单币快照拉取失败就跳过，不挡唤醒。
     */
    private String marketSnapshot(Set<String> whitelist, AgentLang lang) {
        StringBuilder snap = new StringBuilder();
        for (String sym : whitelist.stream().sorted().toList()) {
            try {
                JSONObject p = JSON.parseObject(binanceRestClient.getPremiumIndex(sym));
                snap.append(prompts.get(lang, "trader.wake.snapshotRow", Map.of(
                        "symbol", sym,
                        "price", p.getBigDecimal("markPrice").stripTrailingZeros().toPlainString(),
                        "funding", p.getString("lastFundingRate")))).append('\n');
            } catch (Exception e) {
                log.debug("[Trader] 行情快照拉取失败 {} msg={}", sym, e.getMessage());
            }
        }
        return snap.toString();
    }

    /**
     * 合并轨迹：顺序骨架来自轨迹收集器的全量记录（含数据工具）；交易工具用 TradeTools 的
     * 富记录（结果/拒因）按序替换轻量占位。极端中断时轨迹记录缺失，富记录兜底补尾。
     */
    private static List<JSONObject> mergeActions(List<JSONObject> traced, List<JSONObject> tradeActions) {
        Deque<JSONObject> rich = new ArrayDeque<>(tradeActions);
        List<JSONObject> merged = new ArrayList<>();
        for (JSONObject t : traced) {
            if (TradeTools.RECORDED_TOOLS.contains(t.getString("tool")) && !rich.isEmpty()) {
                merged.add(rich.poll());
            } else {
                merged.add(t);
            }
        }
        merged.addAll(rich);
        return merged;
    }

    /**
     * 权益 = 可用余额 + 冻结 + Σ仓位价值。口径对齐 sim AssetValuationService：
     * 全仓仓位只计浮盈亏——占用制下保证金从没离开余额钱包，再加 margin 就是同一笔钱计两遍
     * （曾造成竞技场亏损却显示 +1281 的虚高）；逐仓才是划扣制，margin 住在仓位里要加回。
     */
    private BigDecimal computeEquity(Long simUserId, List<FuturesPositionDTO> positions) {
        Map<String, Object> balance = simTradeClient.getBalanceDetail(simUserId);
        BigDecimal equity = new BigDecimal(String.valueOf(balance.get("balance")))
                .add(new BigDecimal(String.valueOf(balance.getOrDefault("frozenBalance", "0"))));
        for (FuturesPositionDTO p : positions) {
            if (!FuturesPosition.CROSS.equals(p.getMarginMode()) && p.getMargin() != null) {
                equity = equity.add(p.getMargin());
            }
            if (p.getUnrealizedPnl() != null) {
                equity = equity.add(p.getUnrealizedPnl());
            }
        }
        return equity.setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * 账户状态一次给足（持仓+计划+挂单）：模型不必再花工具预算查户口，
     * 预算留给行情求证。持仓携带交易计划与当前止损止盈——让模型一眼看到
     * "浮亏离止损还远/计划没被证伪"，掐灭恐慌平仓。
     */
    static String accountStateJson(PromptCatalog prompts, AgentLang lang,
                                           BigDecimal equity, List<FuturesPositionDTO> positions,
                                           List<FuturesOrderResponse> pendingOrders,
                                           List<AiTraderPlan> plans, long boundaryTime) {
        Map<String, AiTraderPlan> planByKey = new HashMap<>();
        plans.forEach(p -> planByKey.put(TraderPlanStore.key(p.getSymbol(), p.getSide()), p));
        JSONObject out = new JSONObject();
        out.put("equity", equity.setScale(2, RoundingMode.HALF_UP));
        out.put("positions", positionsJson(prompts, lang, positions, planByKey, boundaryTime));
        // 挂单同样给足：开仓挂单占坑且带着计划（成交后计划全文随持仓回注，这里给轻量版）。
        // 挂出时刻与已挂时长必须在：限价单挂了多久只有代码知道，模型据此执行自己写的作废条件
        if (pendingOrders != null && !pendingOrders.isEmpty()) {
            out.put("pendingOrders", pendingOrdersJson(prompts, lang, pendingOrders, planByKey, boundaryTime));
        }
        return out.toJSONString();
    }

    /** 持仓行：仓位事实 + 当前止损止盈 + 所属计划（含修订历史）。 */
    private static JSONArray positionsJson(PromptCatalog prompts, AgentLang lang,
                                           List<FuturesPositionDTO> positions,
                                           Map<String, AiTraderPlan> planByKey, long boundaryTime) {
        JSONArray ps = new JSONArray();
        for (FuturesPositionDTO p : positions) {
            JSONObject row = new JSONObject()
                    .fluentPut("positionId", p.getId())
                    .fluentPut("symbol", p.getSymbol())
                    .fluentPut("side", p.getSide())
                    .fluentPut("quantity", p.getQuantity())
                    .fluentPut("leverage", p.getLeverage())
                    .fluentPut("entryPrice", p.getEntryPrice())
                    .fluentPut("markPrice", p.getMarkPrice())
                    .fluentPut("liquidationPrice", p.getLiquidationPrice())
                    .fluentPut("unrealizedPnl", p.getUnrealizedPnl());
            if (p.getStopLosses() != null && !p.getStopLosses().isEmpty()) {
                row.put("currentStopLoss", p.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList());
            }
            if (p.getTakeProfits() != null && !p.getTakeProfits().isEmpty()) {
                row.put("currentTakeProfit", p.getTakeProfits().stream().map(FuturesTakeProfit::getPrice).toList());
            }
            AiTraderPlan plan = planByKey.get(TraderPlanStore.key(p.getSymbol(), p.getSide()));
            if (plan != null) {
                JSONObject planJson = new JSONObject()
                        .fluentPut("playType", plan.getPlayType())
                        .fluentPut("signalsUsed", plan.getSignalsUsed())
                        .fluentPut("invalidationCondition", plan.getInvalidationCondition())
                        .fluentPut("entryPrice", plan.getEntryPrice())
                        .fluentPut("originalStop", plan.getStopLossPrice())
                        .fluentPut("target", plan.getTakeProfitPrice())
                        .fluentPut("openedAt", TIME_FMT.format(Instant.ofEpochMilli(plan.getOpenedWakeTime())))
                        .fluentPut("heldFor", humanizeHeld(prompts, lang, boundaryTime - plan.getOpenedWakeTime()));
                // 修订历史也回注：无记忆的模型必须看到"上轮为什么动了止损/目标"
                if (plan.getRevisionsJson() != null && !plan.getRevisionsJson().isBlank()) {
                    JSONArray revisions = JSON.parseArray(plan.getRevisionsJson());
                    for (int i = 0; i < revisions.size(); i++) {
                        JSONObject r = revisions.getJSONObject(i);
                        // 库里存 epoch 毫秒，给模型看要时刻
                        r.put("time", TIME_FMT.format(Instant.ofEpochMilli(r.getLongValue("time"))));
                    }
                    planJson.put("revisions", revisions);
                }
                row.put("plan", planJson);
            }
            ps.add(row);
        }
        return ps;
    }

    /** 挂单行：订单事实 + 开仓挂单所属计划的轻量版。 */
    private static JSONArray pendingOrdersJson(PromptCatalog prompts, AgentLang lang,
                                               List<FuturesOrderResponse> pendingOrders,
                                               Map<String, AiTraderPlan> planByKey, long boundaryTime) {
        JSONArray po = new JSONArray();
        for (FuturesOrderResponse o : pendingOrders) {
            JSONObject row = new JSONObject()
                    .fluentPut("orderId", o.getOrderId())
                    .fluentPut("symbol", o.getSymbol())
                    .fluentPut("orderSide", o.getOrderSide())
                    .fluentPut("quantity", o.getQuantity())
                    .fluentPut("limitPrice", o.getLimitPrice())
                    .fluentPut("leverage", o.getLeverage());
            if (o.getOrderSide() != null && o.getOrderSide().startsWith("OPEN_")) {
                AiTraderPlan plan = planByKey.get(TraderPlanStore.key(o.getSymbol(),
                        o.getOrderSide().substring("OPEN_".length())));
                if (plan != null) {
                    row.put("plan", new JSONObject()
                            .fluentPut("playType", plan.getPlayType())
                            .fluentPut("invalidationCondition", plan.getInvalidationCondition())
                            .fluentPut("placedAt", TIME_FMT.format(Instant.ofEpochMilli(plan.getOpenedWakeTime())))
                            .fluentPut("pendingFor", humanizeHeld(prompts, lang, boundaryTime - plan.getOpenedWakeTime())));
                }
            }
            po.add(row);
        }
        return po;
    }

    private static String humanizeHeld(PromptCatalog prompts, AgentLang lang, long ms) {
        long min = Math.max(0, ms / 60_000);
        if (min < 120) {
            return prompts.get(lang, "trader.wake.held.minutes", Map.of("n", min));
        }
        long hours = min / 60;
        return hours < 48
                ? prompts.get(lang, "trader.wake.held.hours", Map.of("n", hours))
                : prompts.get(lang, "trader.wake.held.days", Map.of("n", hours / 24));
    }

    private static AiTraderDecision baseDecision(AiTrader trader, long boundaryTime) {
        AiTraderDecision d = new AiTraderDecision();
        d.setTraderId(trader.getId());
        d.setRoundNo(trader.getRoundNo());
        d.setWakeTime(boundaryTime);
        d.setIntervalCode(trader.getIntervalCode());
        d.setKind(AiTraderDecision.KIND_TRADE);
        d.setToolCalls(0);
        return d;
    }

    // 状态回写一律列级更新：runner 手里的 trader 是调度时刻的快照，整行 updateById 会把
    // 用户并发修改的配置（提示词/模型等）覆盖回旧值

    /**
     * 唤醒开头的权益体检判出局
     */
    private void markBustOut(AiTrader trader, AiTraderDecision decision, AgentLang lang) {
        decision.setStatus(AiTraderDecision.STATUS_OK);
        decision.setReasoning(prompts.get(lang, "trader.error.liquidatedReasoning"));
        decisionMapper.insert(decision);
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, trader.getId())
                .set(AiTrader::getStatus, AiTrader.STATUS_LIQUIDATED)
                .set(AiTrader::getPausedReason, prompts.get(lang, "trader.error.liquidatedReason"))
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        log.info("[Trader] 爆仓终局 traderId={} round={}", trader.getId(), trader.getRoundNo());
    }

    private void recordFailure(AiTrader trader, String lastError, AgentLang lang, boolean keyInvalid) {
        int failures = (trader.getConsecutiveFailures() == null ? 0 : trader.getConsecutiveFailures()) + 1;
        LambdaUpdateWrapper<AiTrader> update = new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, trader.getId())
                .set(AiTrader::getConsecutiveFailures, failures)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now());
        // key 无效是永久错误：再攒够连败也只是原样重炸几轮，白烧调度还让用户多等几个周期。
        // 能修的人只有用户自己，所以立刻停、把原因写成他看得懂的话
        if (keyInvalid) {
            update.set(AiTrader::getStatus, AiTrader.STATUS_PAUSED)
                    .set(AiTrader::getPausedReason, prompts.get(lang, "trader.error.keyInvalid"));
            log.warn("[Trader] key 失效自动暂停 traderId={}", trader.getId());
        } else if (failures >= MAX_CONSECUTIVE_FAILURES) {
            // 原因嵌在暂停提示那一句里显示在 trader 卡片上，留 150 字够说清是什么错
            update.set(AiTrader::getStatus, AiTrader.STATUS_PAUSED)
                    .set(AiTrader::getPausedReason, prompts.get(lang, "trader.error.consecutiveFailures",
                            Map.of("n", failures, "error",
                                    lastError.length() > 150 ? lastError.substring(0, 150) : lastError)));
            log.warn("[Trader] 连败自动暂停 traderId={} failures={}", trader.getId(), failures);
        }
        traderMapper.update(null, update);
    }

    private void clearFailures(AiTrader trader) {
        if (trader.getConsecutiveFailures() != null && trader.getConsecutiveFailures() > 0) {
            traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                    .eq(AiTrader::getId, trader.getId())
                    .set(AiTrader::getConsecutiveFailures, 0)
                    .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        }
    }
}
