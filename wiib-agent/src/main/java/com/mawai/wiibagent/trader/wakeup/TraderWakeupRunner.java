package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.TradeEvent;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.ToolCallTraceHook;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.learning.ExperienceLedger;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.toolkit.CrossAssetToolkit;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.toolkit.DerivativesToolkit;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.toolkit.WhaleToolkit;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.prompt.EconCalendarAssembler;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.trader.trade.EntryContext;
import com.mawai.wiibagent.trader.trade.TradeTools;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 唤醒回路核心：一次唤醒 = 一个无状态 ReactLoop 会话（BYOK 模型 + 数据工具 + 绑定子账户的交易工具），
 * 决策全文 + 动作轨迹 + 权益落 ai_trader_decision。
 * 开场白分两段：第一段只处置自己留下的承诺（持仓、挂单、盯盘条件、上一轮的等待），答完再追加第二段看整盘、布防、收尾；
 * 没承诺要处置、没留言、也不是非例行唤醒的轮次并成一段。
 * 失败语义：连续 5 次失败自动 PAUSED（key 无效是永久错误，不等连败当场停）；权益跌破初始 1% 判 LIQUIDATED 终局。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderWakeupRunner {

    /** 距下一边界不足此秒数=触发过晚（事件迟到），放弃本轮不算失败 */
    static final int MIN_WAKE_SECONDS = 30;
    /** 单轮预算上限：再慢的端点也不许无限吃时长 */
    static final int MAX_WAKE_SECONDS = 900;
    /** 截止安全余量：唤醒决不占用下一根K线 */
    private static final long DEADLINE_SAFETY_MS = 5_000;
    /**
     * 单次唤醒模型调用上限（ReAct 迭代保险丝，挡住无限工具循环烧用户的钱）。
     * <p>
     * 值给到 12 是因为并不并行差得远：会并行的模型一轮发 4~9 个 tool_call，2~3 次调用就取完数据；
     * 不并行的一轮一个，3 币多周期求证根本走不完。撞上限不算失败（见下面 setError 那段），
     * 但收束时最后一条是纯 tool_call、正文空，这轮决策就没有收尾的结论块——
     * 下一轮的【上一轮结论】和复盘素材都跟着缺。
     */
    public static final int MAX_MODEL_CALLS = 12;
    static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** 出局判定线：权益 < 初始资金 10000 的 1% */
    static final BigDecimal BUST_OUT_FLOOR = new BigDecimal("100");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    /** 休眠提示里的时刻按北京时间（与 WakeWindow.ZONE 同源）：时段是北京时间，下次时刻也得是，否则容器 TZ 非 +8 时两句会错位 */
    private static final DateTimeFormatter BJ_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(WakeWindow.ZONE);
    /** 结论里引用账本条目的写法：#编号 */
    private static final Pattern LEDGER_REF = Pattern.compile("#(\\d{1,18})(?!\\d)");
    /** 一批触发合成一轮时决策行的 kind：按要紧程度，持仓事件 > 盯盘命中 > 波动警报 */
    private static final List<String> TRIGGER_KIND_PRIORITY =
            List.of(AiTraderDecision.KIND_EVENT, AiTraderDecision.KIND_WATCH, AiTraderDecision.KIND_ALERT);

    private final TraderModelFactory modelFactory;
    private final TraderPromptAssembler promptAssembler;
    private final SimTradeClient simTradeClient;
    private final BinanceRestClient binanceRestClient;
    private final IndicatorToolkit indicatorToolkit;
    private final MarketToolkit marketToolkit;
    private final DerivativesToolkit derivativesToolkit;
    private final WhaleToolkit whaleToolkit;
    private final NewsToolkit newsToolkit;
    private final CrossAssetToolkit crossAssetToolkit;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final TraderPlanStore planStore;
    private final TraderWatchStore watchStore;
    private final UserLangResolver userLangResolver;
    private final PromptCatalog prompts;
    /** sim 拒因按错误码成文（见 SimTradeClient.describe），同样跟 trader 主人的语言 */
    private final MessageCatalog messages;
    private final LocalizedToolCallbacks localizedTools;
    /** 观察包：事件+账户+上轮结论+最近轨迹+论点战绩，例行/警报开场白共用这一份 */
    private final WakeObservation wakeObservation;
    /** 财经日历注入块（过去12h已公布+未来24h即将公布）；null=无相关事件或取数失败，整块缺席 */
    private final EconCalendarAssembler econCalendar;
    /** 唤醒现场：过程逐帧推给竞技场订阅者，轨迹落 trace_json */
    private final TraderLiveHub hub;
    /** 开仓那一刻的上下文快照，随计划落库 */
    private final EntryContext entryContext;
    private final DecisionText decisionText;
    /** 经验账本：结论里引用的 #编号计一次引用 */
    private final ExperienceLedger ledger;

    /** 墙钟注入点：预算计算要可测（测试里把"现在"钉在边界附近） */
    LongSupplier nowMs = System::currentTimeMillis;

    /** 唤醒预算(秒)：截止 = 下一边界前 5s——唤醒决不占用下一根K线；上限 900s。 */
    static long wakeBudgetSeconds(long boundary, long intervalMs, long now) {
        long deadline = boundary + intervalMs - DEADLINE_SAFETY_MS;
        return Math.min((deadline - now) / 1000, MAX_WAKE_SECONDS);
    }

    /** 非例行唤醒预算(秒)：上限 900s，且不超过一根心跳减 5s，跑过边界最多跳过一次例行 */
    static long triggeredBudgetSeconds(long intervalMs) {
        return Math.min(MAX_WAKE_SECONDS, (intervalMs - DEADLINE_SAFETY_MS) / 1000);
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
        long intervalMs = TraderScheduler.INTERVAL_MS.get(trader.getIntervalCode());
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
     * 非例行唤醒（波动警报 / 持仓事件）：wake_time=真正醒来的时刻（非K线边界；排过队的晚于触发时刻，
     * 各条触发自己的时刻写在开场白里）。预算见 {@link #triggeredBudgetSeconds}，不按下一边界截：跑过边界时那根的例行轮跳过（调度器 SKIPPED 留痕）。
     */
    public void wakeTriggered(AiTrader trader, List<WakeTrigger> triggers) {
        long wakeTime = nowMs.getAsLong();
        AiTraderDecision decision = baseDecision(trader, wakeTime);
        // 一批里的 kind 按要紧程度取：持仓事件 > 盯盘命中 > 波动警报
        decision.setKind(TRIGGER_KIND_PRIORITY.stream()
                .filter(k -> triggers.stream().anyMatch(t -> k.equals(t.kind()))).findFirst().orElseThrow());
        doWake(trader, wakeTime, triggeredBudgetSeconds(TraderScheduler.INTERVAL_MS.get(trader.getIntervalCode())),
                decision, triggers, userLangResolver.of(trader.getUserId()));
    }

    private void doWake(AiTrader trader, long boundaryTime, long budgetSeconds,
                        AiTraderDecision decision, List<WakeTrigger> triggers, AgentLang lang) {
        long start = System.currentTimeMillis();
        // 本轮截止时刻：备料、下单护栏、超时计时都在这一份预算里
        long deadline = start + budgetSeconds * 1000;
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
            String reasoning = runAgentSession(trader, boundaryTime, deadline, positions, pendingOrders, equity, decision, triggers, lang, run);
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
            recordCitations(trader, decision);
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

    /** 结论块里引用的账本 #编号各计一次：本轮有成交的 open_position 算开仓引用，否则算观望引用；记不上只 warn */
    private void recordCitations(AiTrader trader, AiTraderDecision decision) {
        try {
            String block = decisionText.conclusionBlock(decision.getReasoning());
            if (block == null) {
                return;
            }
            Set<Long> ids = new LinkedHashSet<>();
            Matcher m = LEDGER_REF.matcher(block);
            while (m.find()) {
                ids.add(Long.parseLong(m.group(1)));
            }
            if (ids.isEmpty()) {
                return;
            }
            boolean opened = false;
            for (JsonNode a : MAPPER.readTree(decision.getActionsJson())) {
                if ("open_position".equals(a.path("tool").asString(null)) && "ok".equals(a.path("status").asString(null))) {
                    opened = true;
                }
            }
            ledger.recordCitations(trader.getId(), trader.getRoundNo(), ids, opened);
        } catch (Exception e) {
            log.warn("[Trader] 账本引用计数失败 traderId={} msg={}", trader.getId(), e.toString());
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
     * 返回模型最终文本；动作轨迹随 decision 一并写入。triggers 非空=非例行唤醒（只换开场白）。
     */
    private String runAgentSession(AiTrader trader, long boundaryTime, long deadline,
                                   List<FuturesPositionDTO> positions, List<FuturesOrderResponse> pendingOrders,
                                   BigDecimal equity, AiTraderDecision decision, List<WakeTrigger> triggers,
                                   AgentLang lang, TraderLiveHub.Run run) throws Exception {
        // 用量统计装饰器 每轮新建
        UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
        Set<String> whitelist = Arrays.stream(trader.getSymbols().split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        TradeTools tradeTools = tradeToolsFor(trader, whitelist, boundaryTime, deadline, lang);
        // 计划对照 sim 持仓/挂单：了结的归档并报结局、限价成交的绑上仓位，存活的随持仓注入账户状态；已平仓位只在有事时才查
        TraderPlanStore.Reconcile reconcile = planStore.reconcile(trader.getId(), trader.getRoundNo(), positions, pendingOrders,
                () -> simTradeClient.getClosedPositions(trader.getSimUserId(), PlayStatsAssembler.FETCH_LIMIT), boundaryTime, lang);
        // 本局全部计划（含归档）：最近轮的 stale 过滤要按计划生命期判段落归属
        List<AiTraderPlan> plans = planStore.listAll(trader.getId(), trader.getRoundNo());
        // 获取最近的唤醒轮，并过滤掉stale内容
        List<WakeObservation.RecentWake> recent = wakeObservation.recentWakes(trader, boundaryTime, plans);
        // system 提示词：平台模板 + 经验账本 + 自定义指令 + 固定收尾格式 + 输出语言
        String prompt = promptAssembler.assemble(trader, lang);
        // 命中了还没哪轮告诉过模型的盯盘条件，分三份：本轮触发源里的（首行念）、这根收盘刚命中的收盘类（例行轮并进去念）、其余的（对账块补报）
        Set<Long> triggerWatchIds = triggers == null ? Set.of() : triggers.stream()
                .filter(WatchTrigger.class::isInstance).map(t -> ((WatchTrigger) t).watch().getId()).collect(Collectors.toSet());
        List<AiTraderWatch> unreported = watchStore.firedUnreported(trader.getId(), trader.getRoundNo());
        List<AiTraderWatch> hitsNow = triggers != null ? List.of() : unreported.stream()
                .filter(w -> w.getFiredAt() == boundaryTime).toList();
        List<AiTraderWatch> quiet = unreported.stream()
                .filter(w -> !triggerWatchIds.contains(w.getId()) && !hitsNow.contains(w)).toList();
        // 各币标记价与资金费率只拉一次：快照行和承诺块的"离价位多远"共用
        Map<String, JsonNode> quotes = quotes(whitelist);
        Map<String, BigDecimal> marks = new HashMap<>();
        quotes.forEach((symbol, q) -> marks.put(symbol, q.path("markPrice").asDecimal(null)));
        // 第一段的观察包：上次醒来后的事件 + 账户（持仓/挂单带计划）+ 上一轮结论 + 承诺
        WakeObservation.Pack pack = wakeObservation.assemble(trader, whitelist, equity, positions, pendingOrders, reconcile, recent,
                boundaryTime, lang, hitsNow, quiet, marks);
        // 留言进开场白不进 system；取一次就消费一轮。只给例行/手动轮：警报/事件轮在 K 线中间，主人留的话多半是按收盘说的
        String ownerNote = triggers != null ? "" : promptAssembler.ownerNoteBlock(trader, lang);
        // 两段还是一段：非例行唤醒、有留言、有承诺要处置就分两段，否则并成一段
        boolean twoStage = triggers != null || !ownerNote.isEmpty() || pack.busy();

        // 上一边界之后公布的算"刚公布"：例行轮就是上次唤醒以来；警报/手动的 boundaryTime 是触发时刻，对齐后同一个意思
        long intervalMs = TraderScheduler.INTERVAL_MS.get(trader.getIntervalCode());
        long since = boundaryTime - Math.floorMod(boundaryTime, intervalMs) - intervalMs;
        String calendar = econCalendar.assemble(nowMs.getAsLong(), since, lang);
        // 日历只出现一次：非例行唤醒放第一段（归因要用），例行轮放第二段。单段的整盘事实现在就算，两段的到追加第二段时再算
        String instruction = twoStage
                ? stageOneInstruction(trader, triggers, boundaryTime, lastRealWake(recent), pack.text(),
                        triggers == null ? null : calendar, ownerNote, lang)
                : routineInstruction(trader, boundaryTime, pack.text(),
                        wakeObservation.symbolsSummary(trader, whitelist, pack.lastOkWakeMs(), boundaryTime, lang),
                        wakeObservation.history(trader, recent, lang), snapshotRows(quotes, lang), calendar, lang);

        // 全量工具轨迹（含数据工具）：收集器在本方法手里，超时 cancel 也保得住已发生的记录
        ToolCallTraceHook trace = new ToolCallTraceHook();
        // 不强制 tool_choice："先读事实、用工具求证"写在开场白的问题里
        LlmClient client = LlmClient.builder().model(model).systemPrompt(prompt)
                .tools(wakeTools(lang, tradeTools)).build();
        ReactLoop loop = ReactLoop.builder().client(client)
                .streaming(true)      // 模型文本逐字推给现场
                .limiter(new ModelCallLimiter(MAX_MODEL_CALLS, prompts.get(lang, "llm.callLimit.notExecuted"),
                        prompts.get(lang, "llm.callLimit.lastCall")))
                .trace(trace)
                // 数据工具同批并行，批里有动作工具就整批串行
                .serialTools(TradeTools.SERIAL_TOOLS)
                // 第一段答完追加第二段：整盘事实 + 布防 + 收尾
                .stages(twoStage ? stageTwo(trader, boundaryTime, whitelist, pack.lastOkWakeMs(), recent,
                        triggers == null ? calendar : null, lang) : null)
                .build();
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
                    } else if (m instanceof UserMessage stage) {
                        // 第二段开场白进了历史：现场记一道分界，下一次调用从这儿起
                        run.stage(stage.getText());
                        run.callStart();
                    }
                }
            });
            return new SessionOutcome(finalReasoning(prompts, lang, result.messages()), result.modelCalls());
        });
        Thread.startVirtualThread(task);
        SessionOutcome outcome;
        try {
            outcome = task.get(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        } finally {
            cancel.complete(null);
            task.cancel(true);
            // 无论成败，动作轨迹都要留：超时/异常时已执行的开平仓是真实发生的
            List<ObjectNode> actions = mergeActions(trace.calls(), tradeTools.actions());
            decision.setActionsJson(MAPPER.writeValueAsString(actions));
            decision.setToolCalls(actions.size());
            // 用量同理落在 finally：超时作废的那一轮，token 也是真烧掉了，不能不记
            UsageTrackingChatModel.UsageSnapshot usage = model.snapshot();
            decision.setModelCalls(usage.modelCalls());
            decision.setPromptTokens(usage.promptTokens());
            decision.setCompletionTokens(usage.completionTokens());
            decision.setTotalTokens(usage.totalTokens());
            // 开场白里念给它的盯盘命中（触发源里的、这根收盘的、补报的）：模型读到过一次就算告诉过了，超时作废的轮也算
            if (usage.modelCalls() > 0) {
                List<Long> told = new ArrayList<>(triggerWatchIds);
                hitsNow.forEach(w -> told.add(w.getId()));
                quiet.forEach(w -> told.add(w.getId()));
                try {
                    watchStore.markWoken(told, decision.getWakeTime());
                } catch (Exception e) {
                    // 记账失败不改这一轮的结局；没标上的下一轮开场白再报一次
                    log.warn("[Wakeup] 盯盘命中标记已告知失败 traderId={} msg={}", decision.getTraderId(), e.toString());
                }
            }
        }
        if (outcome.modelCalls() >= MAX_MODEL_CALLS) {
            // 保险丝收束不算失败（已有动作真实生效），但必须留痕——否则时间线上像正常决策
            decision.setError(prompts.get(lang, "trader.error.callLimit", Map.of("limit", MAX_MODEL_CALLS)));
        }
        return outcome.reasoning();
    }

    /** 上次真跑过的唤醒时刻：SKIPPED 行（上轮未完跳过、触发过晚）没跑模型，不算醒过 */
    private static Long lastRealWake(List<WakeObservation.RecentWake> recent) {
        for (WakeObservation.RecentWake w : recent) {
            if (!AiTraderDecision.STATUS_SKIPPED.equals(w.status())) {
                return w.wakeTime();
            }
        }
        return null;
    }

    /** 交易工具：绑定该 trader 的 sim 子账户、白名单与风险规格，每次唤醒 new 一个。权益按开仓时现查的持仓算 */
    private TradeTools tradeToolsFor(AiTrader trader, Set<String> whitelist, long boundaryTime, long deadline, AgentLang lang) {
        return new TradeTools(
                simTradeClient,
                trader.getSimUserId(),
                whitelist,
                positions -> computeEquity(trader.getSimUserId(), positions),
                sym -> MAPPER.readTree(binanceRestClient.getPremiumIndex(sym)).path("markPrice").asDecimal(null),
                planStore,
                watchStore,
                new TradeTools.WakeCtx(trader.getId(), trader.getRoundNo(), trader.getIntervalCode(), boundaryTime,
                        deadline, TraderRiskConfig.of(trader), lang),
                prompts,
                messages,
                entryContext
        );
    }

    /**
     * 唤醒挂的整套工具。工具描述按语言取自词表 {@code tool.<工具名>}（词表没这条就用注解原描述）。
     * <p>
     * 包私有：单测直接调它，验的才是建循环时真正挂上去的那批工具，而不是测试里另抄一份清单。
     * <p>
     * 快讯工具的语言在这里烤进实例（英文取译文，缺译文回落中文原文）——取哪门语言的新闻不是模型的选择。
     */
    List<ToolCallback> wakeTools(AgentLang lang, TradeTools tradeTools) {
        return localizedTools.of(lang, tradeTools, indicatorToolkit, marketToolkit, derivativesToolkit, whaleToolkit,
                crossAssetToolkit, newsToolkit.boundTo(lang));
    }

    /**
     * 决策正文：每段取最近一条有正文的助手消息，段与段用空行接起来——第一段里为什么平仓、为什么移止损，
     * 和第二段的结论一样要进公开的决策行。
     * 取"最近一条有正文的"而不是死盯段末那条：保险丝在工具边收束时，末尾是纯 tool_call 的助手消息 + 未执行占位回执，
     * 正文都是空的，死盯就会写出 status=OK 却一个字没有的决策行，时间线上与正常决策无从区分。
     */
    private static String finalReasoning(PromptCatalog prompts, AgentLang lang, List<Message> messages) {
        List<String> parts = new ArrayList<>();
        String last = null;
        for (Message m : messages) {
            if (m instanceof UserMessage) {
                // 开场白和每段的开场白都是 user 消息：段落边界
                if (last != null) {
                    parts.add(last);
                }
                last = null;
            } else if (m instanceof AssistantMessage assistant
                    && assistant.getText() != null && !assistant.getText().isBlank()) {
                last = assistant.getText();
            }
        }
        if (last != null) {
            parts.add(last);
        }
        return parts.isEmpty() ? prompts.get(lang, "trader.wake.noReasoning") : String.join("\n\n", parts);
    }

    /**
     * 单段开场白（没有承诺要处置、没留言、也不是非例行唤醒）：头 + 观察包 + 整盘事实 + 本轮任务。
     * <p>
     * 收尾标记取 {@code trader.mark.conclusion}，与系统提示词同一条 key——两处必须同源。
     * <p>
     * 包私有非 private：标记同源那条钉子（{@code WakeInstructionI18nTest}）要拿它的成文比对。
     *
     * @param observation 第一段观察包（{@link WakeObservation#assemble}），空串=不带。紧跟头部事实
     * @param symbols     各币摘要块（{@link WakeObservation#symbolsSummary}），空串=不带
     * @param history     最近轨迹 + 论点战绩（{@link WakeObservation#history}），空串=不带
     * @param calendar    财经日历块（{@link EconCalendarAssembler}），null=整块缺席。
     *                    与快照同属事实区，排在快照之后、休眠提示与本轮任务之前
     */
    String routineInstruction(AiTrader trader, long wakeTime, String observation, String symbols, String history,
                              String snapshot, String calendar, AgentLang lang) {
        long intervalMs = TraderScheduler.INTERVAL_MS.get(trader.getIntervalCode());
        long boundaryTime = alignedBoundary(wakeTime, intervalMs);
        return routineHeader(trader, boundaryTime, lang)
                + observation
                + facts(trader, boundaryTime, intervalMs, symbols, history, snapshot, calendar, lang)
                + prompts.get(lang, "trader.wake.routineQuestion",
                        Map.of("mark", prompts.get(lang, "trader.mark.conclusion")));
    }

    /**
     * 第一段「处置」开场白：为什么醒 + 观察包（事件 / 账户 / 上一轮结论 / 承诺）+ 第一段的问题，留言压在最末
     * （user 消息末尾是最近因位置，留言在这儿才是"本轮要回答的问题之一"）。
     * 非例行唤醒（triggers 非空）的头是触发行加"上次唤醒"那行，日历跟在观察包后面——被叫醒时"刚才是否有数据公布"
     * 正是归因的关键事实；问题前多一句"当前 K 线未收盘"。留言只给例行轮。
     *
     * @param calendar 财经日历块，只有非例行唤醒放这儿，例行轮它在第二段；null=整块缺席
     */
    String stageOneInstruction(AiTrader trader, List<WakeTrigger> triggers, long wakeTime, Long lastWakeTime,
                               String observation, String calendar, String ownerNote, AgentLang lang) {
        String mark = prompts.get(lang, "trader.mark.conclusion");
        if (triggers == null) {
            long intervalMs = TraderScheduler.INTERVAL_MS.get(trader.getIntervalCode());
            return routineHeader(trader, alignedBoundary(wakeTime, intervalMs), lang)
                    + observation
                    + prompts.get(lang, "trader.wake.stageQuestion", Map.of("mark", mark))
                    + ownerNote;
        }
        return triggerHead(trader, triggers, wakeTime, lastWakeTime, lang)
                + observation
                + (calendar == null ? "" : calendar)
                + prompts.get(lang, "trader.wake.triggerNotice", Map.of("interval", trader.getIntervalCode())) + '\n'
                + prompts.get(lang, "trader.wake.triggerQuestion", Map.of("mark", mark));
    }

    /**
     * 第二段「布防与收尾」开场白：头一句 + 整盘事实 + 第二段的问题。
     * 预算在第一段用尽的（exhausted）换成只许收尾的问题：工具一个都调不了，set_watch 也不行
     */
    String stageTwoInstruction(AiTrader trader, long wakeTime, String symbols, String history, String snapshot,
                               String calendar, AgentLang lang, boolean exhausted) {
        long intervalMs = TraderScheduler.INTERVAL_MS.get(trader.getIntervalCode());
        return prompts.get(lang, "trader.wake.stageTwoHeader") + '\n'
                + facts(trader, alignedBoundary(wakeTime, intervalMs), intervalMs, symbols, history, snapshot, calendar, lang)
                + prompts.get(lang, exhausted ? "trader.wake.stageTwoExhausted" : "trader.wake.stageTwoQuestion",
                        Map.of("mark", prompts.get(lang, "trader.mark.conclusion")));
    }

    /**
     * 第二段只发一次：第一段答完给它，再问就没有了。整盘事实到追加那一刻才算：
     * 第一段可能跑了几分钟，标记价重拉一次，不拿开场时那份
     */
    private ReactLoop.Stages stageTwo(AiTrader trader, long wakeTime, Set<String> whitelist, Long lastOkWakeMs,
                                      List<WakeObservation.RecentWake> recent, String calendar, AgentLang lang) {
        AtomicBoolean sent = new AtomicBoolean();
        return exhausted -> {
            if (sent.getAndSet(true)) {
                return Optional.empty();
            }
            String symbols = wakeObservation.symbolsSummary(trader, whitelist, lastOkWakeMs, wakeTime, lang);
            String history = wakeObservation.history(trader, recent, lang);
            String snapshot = snapshotRows(quotes(whitelist), lang);
            return Optional.of(new UserMessage(stageTwoInstruction(trader, wakeTime, symbols, history, snapshot, calendar, lang, exhausted)));
        };
    }

    /** 整盘事实区：各币摘要 → 历史（轨迹 / 战绩）→ 快照 → 日历 → 休眠提示；单段与第二段共用 */
    private String facts(AiTrader trader, long boundaryTime, long intervalMs, String symbols, String history,
                         String snapshot, String calendar, AgentLang lang) {
        return symbols
                + history
                + (snapshot.isEmpty() ? "" : "\n" + prompts.get(lang, "trader.wake.snapshotHeader") + "\n" + snapshot)
                + (calendar == null ? "" : "\n" + calendar)
                // 休眠提示放快照之后（事实区）、问题之前：不让"要睡了"成为模型读到的第一件事
                + sleepNotice(prompts, lang, WakeWindow.of(trader), boundaryTime, intervalMs, nowMs.getAsLong());
    }

    /** 手动唤醒的 wakeTime 是按下按钮那一刻，"已收盘的那根"和休眠提示要按对齐边界说；例行轮本来就对齐 */
    private static long alignedBoundary(long wakeTime, long intervalMs) {
        return wakeTime - Math.floorMod(wakeTime, intervalMs);
    }

    private String routineHeader(AiTrader trader, long boundaryTime, AgentLang lang) {
        return prompts.get(lang, "trader.wake.routineHeader", Map.of(
                "interval", trader.getIntervalCode(),
                "time", TIME_FMT.format(Instant.ofEpochMilli(boundaryTime))));
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
     * 非例行唤醒的头（警报 / 持仓事件 / 盯盘命中共用）：先逐条报把它叫醒的事，排队期间攒下的几条按先后一起报、各带自己的时刻；
     * 再是"上次唤醒/距例行"那行。事实全代码注入。了结的净盈亏和当时的计划由观察包的【自上次唤醒以来】对账块承载，不在这里再算一遍。
     *
     * @param wakeTime     真正醒来的时刻(ms)，"距下一次例行"按它算
     * @param lastWakeTime 本局上一次交易唤醒的时刻(ms)，null=本局还没醒过
     */
    private String triggerHead(AiTrader trader, List<WakeTrigger> triggers, long wakeTime, Long lastWakeTime, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        boolean batch = triggers.size() > 1;
        if (batch) {
            sb.append(prompts.get(lang, "trader.wake.triggerBatch", Map.of("n", triggers.size()))).append('\n');
        }
        for (WakeTrigger t : triggers) {
            sb.append(batch ? "- " : "").append(triggerLine(t, lang)).append('\n');
        }
        return sb.append(lastWakeLine(trader, wakeTime, lastWakeTime, lang)).append('\n').toString();
    }

    /** 一条触发的一句话：警报报振幅/方向/现价，事件报币/方向/量/价，盯盘命中报条件与命中价和备注，都带自己的时刻 */
    private String triggerLine(WakeTrigger t, AgentLang lang) {
        String time = TIME_FMT.format(Instant.ofEpochMilli(t.triggeredAt()));
        return switch (t) {
            case AlertTrigger a -> prompts.get(lang, "trader.wake.alertHeader", Map.of(
                    "time", time,
                    "symbol", a.symbol(),
                    "amplitude", a.amplitudePct().stripTrailingZeros().toPlainString(),
                    "direction", prompts.get(lang, "trader.wake.direction." + a.direction().toLowerCase(Locale.ROOT)),
                    "price", a.price().stripTrailingZeros().toPlainString()));
            case EventTrigger e -> prompts.get(lang, "trader.wake.eventHeader", Map.of(
                    "time", time,
                    "detail", eventDetail(e.event(), lang)));
            // 盯盘条件的时刻全按北京时间，和它条件里的时刻、账户块的 nowBeijing 同一口径
            case WatchTrigger w -> prompts.get(lang, "trader.wake.watchHeader", Map.of(
                    "time", BeijingTime.format(w.triggeredAt()),
                    "detail", WatchText.detail(w.watch(), w.price(), prompts, lang),
                    "note", w.watch().getNote()));
        };
    }

    /** 事件一句话：按类型选文案，币/方向/量/价填进去（全仓爆仓没有币和价，qty 是爆掉的仓位数） */
    private String eventDetail(TradeEvent e, AgentLang lang) {
        String key = switch (e.type()) {
            case TradeEvent.STOP_LOSS -> "stopLoss";
            case TradeEvent.TAKE_PROFIT -> "takeProfit";
            case TradeEvent.LIQUIDATION -> "liquidation";
            case TradeEvent.CROSS_LIQUIDATION -> "crossLiquidation";
            case TradeEvent.LIMIT_FILLED -> "limitFilled";
            default -> "other";
        };
        return prompts.get(lang, "trader.wake.event." + key, Map.of(
                "type", e.type(),
                "symbol", String.valueOf(e.symbol()),
                "side", String.valueOf(e.side()),
                "qty", WatchText.num(e.quantity()),
                "price", WatchText.num(e.price())));
    }

    /**
     * "你上次唤醒…，距下一次例行唤醒还有约 N 分钟"：非例行开场白都带这一行。
     * 下次例行要看时段：下一边界落在时段外就是更晚的那个（时段末尾被叫醒，下次醒可能是第二天）。
     */
    private String lastWakeLine(AiTrader trader, long triggeredAt, Long lastWakeTime, AgentLang lang) {
        long intervalMs = TraderScheduler.INTERVAL_MS.get(trader.getIntervalCode());
        long next = triggeredAt - Math.floorMod(triggeredAt, intervalMs) + intervalMs;
        WakeWindow window = WakeWindow.of(trader);
        if (window != null) {
            long inWindow = window.nextBoundaryFrom(next, intervalMs);
            if (inWindow > 0) {
                next = inWindow;
            }
        }
        long toNextMin = Math.max(1, (next - triggeredAt) / 60_000);
        String lastWake = lastWakeTime == null ? prompts.get(lang, "trader.wake.alertNoWake")
                : prompts.get(lang, "trader.wake.alertWakeAt", Map.of(
                        "time", TIME_FMT.format(Instant.ofEpochMilli(lastWakeTime)),
                        "minutes", Math.max(1, (triggeredAt - lastWakeTime) / 60_000)));
        return prompts.get(lang, "trader.wake.alertLastWake", Map.of("lastWake", lastWake, "minutes", toNextMin));
    }

    /** 各币的 premiumIndex 回包（标记价 + 资金费率），一币拉一次、按币码排好；单币拉取失败就没有它，不挡唤醒 */
    private Map<String, JsonNode> quotes(Set<String> whitelist) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (String sym : whitelist.stream().sorted().toList()) {
            try {
                JsonNode p = MAPPER.readTree(binanceRestClient.getPremiumIndex(sym));
                if (p.path("markPrice").asDecimal(null) != null) {
                    out.put(sym, p);
                }
            } catch (Exception e) {
                log.debug("[Trader] 行情快照拉取失败 {} msg={}", sym, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 开场行情快照：各币标记价+资金费率，一行一个——把价格水平先钉进模型的世界，
     * 省下"查户口"的工具轮次；细节与多周期确认仍由模型自己用工具求证。
     */
    private String snapshotRows(Map<String, JsonNode> quotes, AgentLang lang) {
        StringBuilder snap = new StringBuilder();
        quotes.forEach((sym, p) -> snap.append(prompts.get(lang, "trader.wake.snapshotRow", Map.of(
                "symbol", sym,
                "price", p.path("markPrice").asDecimal(null).stripTrailingZeros().toPlainString(),
                "funding", p.path("lastFundingRate").asString(null)))).append('\n'));
        return snap.toString();
    }

    /**
     * 合并轨迹：顺序骨架来自轨迹收集器的全量记录（含数据工具）；交易工具用 TradeTools 的
     * 富记录（结果/拒因）按序替换轻量占位。极端中断时轨迹记录缺失，富记录兜底补尾。
     */
    private static List<ObjectNode> mergeActions(List<ObjectNode> traced, List<ObjectNode> tradeActions) {
        Deque<ObjectNode> rich = new ArrayDeque<>(tradeActions);
        List<ObjectNode> merged = new ArrayList<>();
        for (ObjectNode t : traced) {
            if (TradeTools.RECORDED_TOOLS.contains(t.path("tool").asString(null)) && !rich.isEmpty()) {
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
