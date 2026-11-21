package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.ToolCallTraceHook;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.llm.WebSearchTool;
import com.mawai.wiibagent.learning.ExperienceLedger;
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
import com.mawai.wiibagent.trader.trade.TradeActions;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 唤醒回路核心：一次唤醒 = 一个无状态 ReactLoop 会话（BYOK 模型 + 数据工具 + 绑定子账户的交易工具），
 * 决策全文 + 动作轨迹 + 权益落 ai_trader_decision。
 * 联网轮先核查事实，再独立判断是否搜索，最后交易与收尾；未联网轮保留单段或两段流程。
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
     * 值给到 16 是因为并不并行差得远：会并行的模型一轮发 4~9 个 tool_call，2~3 次调用就取完数据；
     * 不并行的一轮一个，3 币多周期求证根本走不完。撞上限不算失败（见下面 setError 那段），
     * 但收束时最后一条是纯 tool_call、正文空，这轮决策就没有收尾的结论块——
     * 下一轮的【上一轮结论】和复盘素材都跟着缺。
     */
    public static final int MAX_MODEL_CALLS = 16;
    static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** 出局判定线：权益 < 初始资金 10000 的 1% */
    static final BigDecimal BUST_OUT_FLOOR = new BigDecimal("100");
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
    /** 一轮怎么走、每段开场白怎么写 */
    private final WakeFlows flows;
    /** 财经日历注入块（过去12h已公布+未来24h即将公布）；null=无相关事件或取数失败，整块缺席 */
    private final EconCalendarAssembler econCalendar;
    /** 唤醒现场：过程逐帧推给竞技场订阅者，轨迹落 trace_json */
    private final TraderLiveHub hub;
    /** 开仓那一刻的上下文快照，随计划落库 */
    private final EntryContext entryContext;
    private final DecisionText decisionText;
    /** 经验账本：结论里引用的 #编号计一次引用 */
    private final ExperienceLedger ledger;
    /** 平台在采的全部币：盯盘条件可以布在这些币上 */
    private final BinanceProperties binanceProperties;
    /** 预案执行器：醒来先等这个 trader 手上的预案做完 */
    private final PresetExecutor presets;

    /** 墙钟注入点：预算计算要可测（测试里把"现在"钉在边界附近） */
    LongSupplier nowMs = System::currentTimeMillis;

    /** 超时打断会话后最多等它收尾多久，盖过 sim 一次下单连重试（最坏约 22 秒）；测试里调短 */
    Duration sessionStopWait = Duration.ofSeconds(30);

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
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
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
        doWake(trader, wakeTime, budgetSeconds, decision, List.of(), lang);
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
        doWake(trader, wakeTime, triggeredBudgetSeconds(WakeIntervals.MS.get(trader.getIntervalCode())),
                decision, triggers, userLangResolver.of(trader.getUserId()));
    }

    void doWake(AiTrader trader, long wakeTime, long budgetSeconds,
                        AiTraderDecision decision, List<WakeTrigger> triggers, AgentLang lang) {
        long start = System.currentTimeMillis();
        // 本轮截止时刻：备料、下单护栏、超时计时都在这一份预算里
        long deadline = start + budgetSeconds * 1000;
        // begin 之前出的异常没有这一轮现场
        TraderLiveHub.Run run = null;
        try {
            // 这个 trader 有预案正在执行就等它做完，下面读到的账户和命中记录才是做完之后的
            presets.awaitIdle(trader.getId());
            List<FuturesPositionDTO> positions = simTradeClient.getAllPositions(trader.getSimUserId());
            BigDecimal equity = simTradeClient.getEquity(trader.getSimUserId());
            decision.setEquity(equity);
            if (equity.compareTo(BUST_OUT_FLOOR) < 0) {
                markBustOut(trader, decision, lang);
                return;
            }

            List<FuturesOrderResponse> pendingOrders = simTradeClient.getPendingOrders(trader.getSimUserId(), null);
            run = hub.begin(trader, decision.getKind(), decision.getWakeTime(), budgetSeconds, equity, positions.size(), pendingOrders.size());
            Session session = runAgentSession(trader, wakeTime, deadline, positions, pendingOrders, equity, decision, triggers, lang, run);
            decision.setStatus(AiTraderDecision.STATUS_OK);
            decision.setReasoning(session.reasoning());
            try {
                decision.setEquity(simTradeClient.getEquity(trader.getSimUserId()));
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
            // 这一轮成功了，开场白里念过的盯盘命中和对账事件才算告诉过模型
            watchStore.markWoken(session.watchHits(), decision.getWakeTime());
            clearUnreportedEvents(trader);
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
            log.warn("[Trader] 唤醒失败 traderId={} wakeTime={} msg={}", trader.getId(), wakeTime, msg, e);
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
            Set<Long> ids = DecisionText.ledgerRefs(block);
            if (ids.isEmpty()) {
                return;
            }
            boolean opened = false;
            for (JsonNode a : MAPPER.readTree(decision.getActionsJson())) {
                if (TradeActions.opened(a)) {
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

    /** 会话跑完的结果：决策正文，和开场白里念过的盯盘命中 */
    private record Session(String reasoning, List<Long> watchHits) {
    }

    /** 只取一次：第一次调才真去取，取到了之后都给同一份 */
    private static <T> Supplier<T> once(Supplier<T> source) {
        List<T> got = new ArrayList<>(1);
        return () -> {
            if (got.isEmpty()) {
                got.add(source.get());
            }
            return got.getFirst();
        };
    }

    /**
     * ReactLoop 会话：备料（计划/最近几轮/提示词/观察包）→ 选走法、出开场白 → 建工具和循环 → 限时执行，过程逐帧推给现场 {@code run}。
     * 动作轨迹随 decision 一并写入。triggers 为空=例行或手动轮，非空=非例行唤醒。
     */
    private Session runAgentSession(AiTrader trader, long wakeTime, long deadline,
                                   List<FuturesPositionDTO> positions, List<FuturesOrderResponse> pendingOrders,
                                   BigDecimal equity, AiTraderDecision decision, List<WakeTrigger> triggers,
                                   AgentLang lang, TraderLiveHub.Run run) throws Exception {
        // 用量统计装饰器 每轮新建
        UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
        // 本轮是否联网，决定工具列表和走法
        boolean webSearch = modelFactory.webSearchEnabled(trader);
        Set<String> whitelist = Arrays.stream(trader.getSymbols().split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        // 已平仓位这一轮最多拉一次，第一次用到才拉：对账有事时用，开场白里今天净亏损了结的次数也从它数
        Supplier<List<FuturesPositionDTO>> closed = once(
                () -> simTradeClient.getClosedPositions(trader.getSimUserId(), PlayStatsAssembler.FETCH_LIMIT));
        // 计划对照 sim 持仓/挂单：了结的归档并报结局、限价成交的绑上仓位，存活的随持仓注入账户状态
        TraderPlanStore.Reconcile reconcile = planStore.reconcile(trader.getId(), trader.getRoundNo(), positions, pendingOrders,
                closed,
                group -> simTradeClient.getGroupOrders(trader.getSimUserId(), group), wakeTime, lang);
        // 对账事件行，连同之前没成功告诉过模型的
        String planEvents = unreportedEvents(trader, wakeObservation.planEvents(reconcile.events(), lang));
        // 本局全部计划（含归档）：最近轮的 stale 过滤要按计划生命期判段落归属
        List<AiTraderPlan> plans = planStore.listAll(trader.getId(), trader.getRoundNo());
        // 获取最近的唤醒轮，并过滤掉stale内容
        List<WakeObservation.RecentWake> recent = wakeObservation.recentWakes(trader, wakeTime, plans);
        // system 提示词：平台模板 + 经验账本 + 自定义指令 + 固定收尾格式 + 输出语言
        String prompt = promptAssembler.assemble(trader, lang);
        // 命中了还没哪轮告诉过模型的盯盘条件，分三份：本轮触发源里的（首行念）、这根收盘刚命中的收盘类（例行轮并进去念）、其余的（对账块补报）
        Set<Long> triggerWatchIds = triggers.stream()
                .filter(WatchTrigger.class::isInstance).map(t -> ((WatchTrigger) t).watch().getId()).collect(Collectors.toSet());
        List<AiTraderWatch> unreported = watchStore.firedUnreported(trader.getId(), trader.getRoundNo());
        // 例行轮的唤醒时刻就是 K 线边界，收盘类条件命中记的也是这个边界
        List<AiTraderWatch> hitsNow = !triggers.isEmpty() ? List.of() : unreported.stream()
                .filter(w -> w.getFiredAt() == wakeTime).toList();
        List<AiTraderWatch> quiet = unreported.stream()
                .filter(w -> !triggerWatchIds.contains(w.getId()) && !hitsNow.contains(w)).toList();
        List<AiTraderWatch> armed = watchStore.armed(trader.getId(), trader.getRoundNo());
        // 开场的价格和资金费率共用一份：可交易币、大盘参考和盯盘涉及的币都要。
        Set<String> quoted = new LinkedHashSet<>(WakeObservation.board(whitelist));
        armed.forEach(w -> quoted.add(w.getSymbol()));
        Map<String, JsonNode> quotes = wakeObservation.quotes(quoted);
        Map<String, BigDecimal> marks = new HashMap<>();
        quotes.forEach((symbol, q) -> marks.put(symbol, q.path("markPrice").asDecimal(null)));
        // 第一段的观察包：上次醒来后的事件 + 账户（持仓/挂单带计划）+ 上一轮结论 + 承诺
        WakeObservation.Pack pack = wakeObservation.assemble(trader, whitelist, equity, positions, pendingOrders,
                wakeObservation.lossesToday(trader, closed, plans, wakeTime, lang),
                reconcile.live(), planEvents, recent, wakeTime, lang, hitsNow, quiet, armed, marks);
        // 留言进开场白不进 system；取一次就消费一轮。只给例行/手动轮：警报/事件轮在 K 线中间，主人留的话多半是按收盘说的
        String ownerNote = !triggers.isEmpty() ? "" : promptAssembler.ownerNoteBlock(trader, lang);

        // 上一边界之后公布的算"刚公布"：例行轮就是上次唤醒以来；警报/手动的唤醒时刻不在边界上，对齐后同一个意思
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
        long since = WakeIntervals.align(wakeTime, intervalMs) - intervalMs;
        String calendar = econCalendar.assemble(nowMs.getAsLong(), since, lang);
        // 这一轮怎么走在这里定一次，开场白、后面几段、能不能开仓、正文取哪几段都问它
        WakeFlows.Flow flow = flows.choose(new WakeFlows.Brief(trader, whitelist, lang, wakeTime, triggers, recent,
                pack, calendar, ownerNote, quotes), webSearch);
        String instruction = flow.opening();
        TradeTools tradeTools = tradeToolsFor(trader, whitelist, wakeTime, deadline, lang, flow::openingAllowed);

        // 全量工具轨迹（含数据工具）：收集器在本方法手里，超时 cancel 也保得住已发生的记录
        ToolCallTraceHook trace = new ToolCallTraceHook();
        // 不强制 tool_choice："先读事实、用工具求证"写在开场白的问题里
        // 开了联网搜索的多挂一个 web_search：搜索单独发一次请求，用量记在同一轮账上
        LlmClient client = LlmClient.builder().model(model).systemPrompt(prompt)
                .tools(wakeTools(lang, tradeTools, webSearch
                        ? new WebSearchTool(model, prompts.get(lang, "trader.wake.searchInstruction")) : null))
                .build();
        // 被保险丝拦下的调用回的那句占位
        String notExecuted = prompts.get(lang, "llm.callLimit.notExecuted");
        ReactLoop loop = ReactLoop.builder().client(client)
                .streaming(true)      // 模型文本逐字推给现场
                .limiter(new ModelCallLimiter(MAX_MODEL_CALLS, notExecuted, prompts.get(lang, "llm.callLimit.lastCall")))
                .trace(trace)
                // 数据工具同批并行，批里有动作工具就整批串行
                .serialTools(TradeTools.SERIAL_TOOLS)
                .stages(exhausted -> flow.next(exhausted, tradeTools::accountSnapshot))
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
                        flow.replied(assistant);
                        run.callEnd(assistant.getText(), assistant.getToolCalls());
                    } else if (m instanceof ToolResponseMessage responses) {
                        for (ToolResponseMessage.ToolResponse r : responses.getResponses()) {
                            // 回的是保险丝那句占位：这次调用没执行
                            if (notExecuted.equals(r.responseData())) {
                                run.toolSkipped(r.id(), r.name(), r.responseData());
                            } else {
                                run.toolResult(r.id(), r.name(), r.responseData());
                            }
                        }
                        run.callStart();
                    } else if (m instanceof UserMessage stage) {
                        // 新一段的开场白与现场分界
                        run.stage(stage.getText());
                        run.callStart();
                    }
                }
            });
            return new SessionOutcome(finalReasoning(prompts, lang, result.messages(), flow.finalStageOnly()), result.modelCalls());
        });
        Thread sessionThread = Thread.startVirtualThread(task);
        SessionOutcome outcome;
        try {
            outcome = task.get(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        } finally {
            cancel.complete(null);
            task.cancel(true);
            // 等会话线程停下来再收：超时那一刻正在跑的交易调用，这时才把自己的结果记完；最多等 sessionStopWait
            if (!sessionThread.join(sessionStopWait)) {
                log.warn("[Trader] 会话线程 {}s 内没停下，不再等，动作轨迹按已记下的收 traderId={}",
                        sessionStopWait.toSeconds(), trader.getId());
            }
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
            decision.setCachedTokens(usage.cachedTokens());
        }
        if (outcome.modelCalls() >= MAX_MODEL_CALLS) {
            // 保险丝收束不算失败（已有动作真实生效），但必须留痕——否则时间线上像正常决策
            decision.setError(prompts.get(lang, "trader.error.callLimit", Map.of("limit", MAX_MODEL_CALLS)));
        }
        // 开场白里念给它的盯盘命中：触发源里的、这根收盘的、补报的
        List<Long> watchHits = new ArrayList<>(triggerWatchIds);
        hitsNow.forEach(w -> watchHits.add(w.getId()));
        quiet.forEach(w -> watchHits.add(w.getId()));
        return new Session(outcome.reasoning(), watchHits);
    }

    /**
     * 还没成功告诉过模型的对账事件行：库里攒着的加上这次新对出来的。
     * 有新的先存回去，这一轮失败了下一轮接着报，成功了才清（{@link #clearUnreportedEvents}）
     */
    private String unreportedEvents(AiTrader trader, String fresh) {
        String carried = trader.getUnreportedEvents() == null ? "" : trader.getUnreportedEvents();
        if (fresh.isEmpty()) {
            return carried;
        }
        trader.setUnreportedEvents(carried + fresh);
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, trader.getId())
                .set(AiTrader::getUnreportedEvents, trader.getUnreportedEvents())
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        return trader.getUnreportedEvents();
    }

    /** 交易工具：绑定该 trader 的 sim 子账户、白名单与风险规格，每次唤醒 new 一个 */
    private TradeTools tradeToolsFor(AiTrader trader, Set<String> whitelist, long wakeTime, long deadline,
                                     AgentLang lang, BooleanSupplier openingAllowed) {
        return new TradeTools(
                simTradeClient,
                trader.getSimUserId(),
                whitelist,
                Set.copyOf(binanceProperties.getAllCryptoSymbols()),
                sym -> MAPPER.readTree(binanceRestClient.getPremiumIndex(sym)).path("markPrice").asDecimal(null),
                planStore,
                watchStore,
                new TradeTools.WakeCtx(trader.getId(), trader.getRoundNo(), trader.getIntervalCode(), wakeTime,
                        deadline, TraderRiskConfig.of(trader), lang),
                prompts,
                messages,
                entryContext,
                openingAllowed
        );
    }

    /**
     * 唤醒挂的整套工具。工具描述按语言取自词表 {@code tool.<工具名>}（词表没这条就用注解原描述）。
     * <p>
     * 包私有：单测直接调它，验的才是建循环时真正挂上去的那批工具，而不是测试里另抄一份清单。
     * <p>
     * 快讯工具的语言在这里烤进实例（英文取译文，缺译文回落中文原文）——取哪门语言的新闻不是模型的选择。
     *
     * @param webSearch 联网搜索工具，没开联网搜索时为 null，不挂
     */
    List<ToolCallback> wakeTools(AgentLang lang, TradeTools tradeTools, WebSearchTool webSearch) {
        List<Object> toolkits = new ArrayList<>(List.of(tradeTools, indicatorToolkit, marketToolkit, derivativesToolkit,
                whaleToolkit, crossAssetToolkit, newsToolkit.boundTo(lang)));
        if (webSearch != null) {
            toolkits.add(webSearch);
        }
        return localizedTools.of(lang, toolkits.toArray());
    }

    /** 决策正文：联网轮只取最后一段，搜索过程留在现场；未联网轮保留各段说明。 */
    private static String finalReasoning(PromptCatalog prompts, AgentLang lang, List<Message> messages, boolean finalStageOnly) {
        List<String> parts = new ArrayList<>();
        String last = null;
        for (Message m : messages) {
            if (m instanceof UserMessage) {
                // 开场白和每段的开场白都是 user 消息：段落边界
                if (last != null && !finalStageOnly) {
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
     * 合并轨迹：顺序骨架来自轨迹收集器的全量记录（含数据工具）；交易工具用 TradeTools 的
     * 富记录（结果/拒因）替换轻量占位，两边按调用 id（callId）对。
     * 改账户的工具只要进了方法就会留富记录，没留的就是没执行（参数绑不上、被保险丝拦下），占位标成 not_attempted。
     * 极端中断时轨迹记录缺失，富记录兜底补尾。
     */
    static List<ObjectNode> mergeActions(List<ObjectNode> traced, List<ObjectNode> tradeActions) {
        Map<String, ObjectNode> rich = new LinkedHashMap<>();
        tradeActions.forEach(a -> rich.put(a.path("callId").asString(), a));
        List<ObjectNode> merged = new ArrayList<>();
        for (ObjectNode t : traced) {
            ObjectNode own = rich.remove(t.path("callId").asString());
            if (own != null) {
                merged.add(own);
            } else {
                merged.add(TradeTools.SERIAL_TOOLS.contains(t.path("tool").asString())
                        ? t.put("status", TradeActions.NOT_ATTEMPTED) : t);
            }
        }
        merged.addAll(rich.values());
        return merged;
    }

    static AiTraderDecision baseDecision(AiTrader trader, long wakeTime) {
        AiTraderDecision d = new AiTraderDecision();
        d.setTraderId(trader.getId());
        d.setRoundNo(trader.getRoundNo());
        d.setWakeTime(wakeTime);
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

    /** 攒着的对账事件行已经告诉过模型，清掉 */
    private void clearUnreportedEvents(AiTrader trader) {
        if (trader.getUnreportedEvents() != null) {
            traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                    .eq(AiTrader::getId, trader.getId())
                    .set(AiTrader::getUnreportedEvents, null)
                    .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        }
    }
}
