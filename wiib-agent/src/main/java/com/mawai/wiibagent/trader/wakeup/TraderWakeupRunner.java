package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.learning.ExperienceLedger;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.trader.trade.AccountGates;
import com.mawai.wiibagent.trader.trade.EntryContext;
import com.mawai.wiibagent.trader.trade.TradeActions;
import com.mawai.wiibagent.trader.trade.TradeTools;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 唤醒回路：一次唤醒 = 一个无状态 ReactLoop 会话（BYOK 模型 + 数据工具 + 绑定子账户的交易工具），
 * 决策全文 + 动作轨迹 + 权益落 ai_trader_decision。
 * 这里只管一轮的生命周期：算预算、判爆仓、落决策行、记成败；备料在 {@link WakeBriefing}，
 * 走法和每段开场白在 {@link WakeFlows}，挂工具跑循环在 {@link WakeSession}。
 * 整盘轮（例行、手动、不归某一个币的触发）管全部可交易币，单币轮只处理把它叫醒的那个币。
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
    /** 几条触发一起叫醒一轮时决策行的 kind：按要紧程度，持仓事件 > 权益回落警报 > 盯盘命中 > 波动警报 */
    private static final List<String> TRIGGER_KIND_PRIORITY = List.of(AiTraderDecision.KIND_EVENT,
            AiTraderDecision.KIND_DRAWDOWN, AiTraderDecision.KIND_WATCH, AiTraderDecision.KIND_ALERT);

    private final TraderModelFactory modelFactory;
    private final TraderPromptAssembler promptAssembler;
    private final SimTradeClient simTradeClient;
    private final BinanceRestClient binanceRestClient;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final TraderPlanStore planStore;
    private final TraderWatchStore watchStore;
    private final UserLangResolver userLangResolver;
    private final PromptCatalog prompts;
    /** sim 拒因按错误码成文（见 SimTradeClient.describe），同样跟 trader 主人的语言 */
    private final MessageCatalog messages;
    /** 一轮开头的备料 */
    private final WakeBriefing briefing;
    /** 一轮怎么走、每段开场白怎么写 */
    private final WakeFlows flows;
    /** 挂工具、跑循环 */
    private final WakeSession session;
    /** 触发的成文：一轮进行中插进来的话从这里出 */
    private final TriggerText triggerText;
    /** 每个 trader 一个账户闸，交给这一轮的交易工具 */
    private final AccountGates gates;
    /** 开场白里报过的事件行，这一轮成功了从这里清 */
    private final UnreportedEvents unreported;
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

    /** 唤醒预算(秒)：截止 = 下一边界前 5s——唤醒决不占用下一根K线；上限 900s。 */
    static long wakeBudgetSeconds(long boundary, long intervalMs, long now) {
        long deadline = boundary + intervalMs - DEADLINE_SAFETY_MS;
        return Math.min((deadline - now) / 1000, MAX_WAKE_SECONDS);
    }

    /** 非例行唤醒预算(秒)：上限 900s，且不超过一根心跳减 5s */
    static long triggeredBudgetSeconds(long intervalMs) {
        return Math.min(MAX_WAKE_SECONDS, (intervalMs - DEADLINE_SAFETY_MS) / 1000);
    }

    /** 例行唤醒：K 线收盘，整盘轮 */
    public void wake(AiTrader trader, long boundaryTime, WakeRound round) {
        wake(trader, boundaryTime, boundaryTime, AiTraderDecision.KIND_TRADE, round);
    }

    /**
     * 手动唤醒（对话轨 wake_trader，已过 HITL）：回路与例行相同，决策行标 MANUAL——时间线要看得出扳机在人手里。
     * 预算仍按对齐边界算，wakeTime 是按下按钮那一刻——与警报同款，回注查询才带得上同一根K线上的例行决策
     */
    public void wakeManual(AiTrader trader, long boundaryTime, WakeRound round) {
        wake(trader, boundaryTime, nowMs.getAsLong(), AiTraderDecision.KIND_MANUAL, round);
    }

    private void wake(AiTrader trader, long boundaryTime, long wakeTime, String kind, WakeRound round) {
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
        doWake(trader, round, wakeTime, budgetSeconds, decision, List.of(), lang);
    }

    /**
     * 非例行唤醒（波动警报 / 持仓事件 / 盯盘命中 / 权益回落警报）：wake_time=真正醒来的时刻（非K线边界，各条触发自己的时刻写在开场白里）。
     * 预算见 {@link #triggeredBudgetSeconds}，不按下一边界截。
     *
     * @param triggers 把它叫醒的触发：单币轮的都归那个币，整盘轮的都不归某一个可交易币
     */
    public void wakeTriggered(AiTrader trader, List<WakeTrigger> triggers, WakeRound round) {
        long wakeTime = nowMs.getAsLong();
        AiTraderDecision decision = baseDecision(trader, wakeTime);
        decision.setKind(TRIGGER_KIND_PRIORITY.stream()
                .filter(k -> triggers.stream().anyMatch(t -> k.equals(t.kind()))).findFirst().orElseThrow());
        doWake(trader, round, wakeTime, triggeredBudgetSeconds(WakeIntervals.MS.get(trader.getIntervalCode())),
                decision, triggers, userLangResolver.of(trader.getUserId()));
    }

    void doWake(AiTrader trader, WakeRound round, long wakeTime, long budgetSeconds,
                AiTraderDecision decision, List<WakeTrigger> triggers, AgentLang lang) {
        long start = System.currentTimeMillis();
        // 本轮截止时刻：备料、下单护栏、超时计时都在这一份预算里
        long deadline = start + budgetSeconds * 1000;
        // 交给这一轮的触发：把它叫醒的，和进行中插进来的（会话线程往里加）
        List<WakeTrigger> taken = new CopyOnWriteArrayList<>(triggers);
        // begin 之前出的异常没有这一轮现场
        TraderLiveHub.Run run = null;
        try {
            // 这个 trader 有预案正在执行就等它做完，下面读到的账户和命中记录才是做完之后的
            presets.awaitIdle(trader.getId());
            BigDecimal equity = simTradeClient.getEquity(trader.getSimUserId());
            decision.setEquity(equity);
            // 爆仓后不会再有下一轮来对账：先把计划对一遍再判出局，了结的单子才归得了档、进得了复盘
            WakeBriefing.Ledger ledger = briefing.reconcile(trader, wakeTime, lang);
            if (equity.compareTo(BUST_OUT_FLOOR) < 0) {
                markBustOut(trader, decision, lang);
                return;
            }

            run = hub.begin(trader, decision.getKind(), round.scope().symbol(), decision.getWakeTime(), budgetSeconds, equity,
                    ledger.positions().size(), ledger.pendingOrders().size());
            WakeBriefing.Prepared prepared = briefing.prepare(trader, round.scope(), wakeTime, ledger, equity, triggers,
                    round.busyElsewhere(), lang);
            decision.setReasoning(runSession(trader, round, ledger, prepared, taken, deadline, decision, lang, run));
            decision.setStatus(AiTraderDecision.STATUS_OK);
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
            // 这一轮成功了，开场白里念过的、中途插进来的盯盘命中，和报过的事件行，才算告诉过模型
            watchStore.markWoken(prepared.watchHits(), decision.getWakeTime());
            unreported.clear(trader.getId(), prepared.reportedEvents());
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
            // 这一轮没跑成，交给它的权益回落警报记一行，下一次整盘轮的开场白补报
            unreported.add(trader.getId(), taken.stream().filter(EquityDropTrigger.class::isInstance)
                    .map(t -> new UnreportedEvents.Line(null, triggerText.missedEquityDrop((EquityDropTrigger) t, lang))).toList());
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

    /**
     * 选走法、建交易工具、跑会话，返回决策正文。动作轨迹和用量由会话记到 decision 上
     */
    private String runSession(AiTrader trader, WakeRound round, WakeBriefing.Ledger ledger, WakeBriefing.Prepared prepared,
                              List<WakeTrigger> taken, long deadline, AiTraderDecision decision, AgentLang lang,
                              TraderLiveHub.Run run) throws Exception {
        WakeFlows.Brief brief = prepared.brief();
        // 用量统计装饰器 每轮新建
        UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
        // 本轮是否联网，决定工具列表和走法
        boolean webSearch = modelFactory.webSearchEnabled(trader);
        // system 提示词：平台模板 + 经验账本 + 自定义指令 + 固定收尾格式 + 输出语言
        String prompt = promptAssembler.assemble(trader, lang);
        // 这一轮怎么走在这里定一次，开场白、后面几段、能不能开仓、正文取哪几段都问它
        WakeFlows.Flow flow = flows.choose(brief, webSearch);
        TradeTools tradeTools = tradeToolsFor(trader, brief.scope(), brief.wakeTime(), deadline, lang, flow);
        // 开场白里给过的账户算这一轮看到过，之后动手前拿现状和它比
        tradeTools.seeOpening(ledger.positions(), ledger.pendingOrders(), prepared.armed(), ledger.lastActionNo());
        // 整盘的联网轮前两段不许开仓，中途这个币来了触发才放开，插话里要说一声
        boolean unlocks = !brief.scope().single() && webSearch;
        WakeSession.Outcome outcome = session.run(model, webSearch, prompt, flow, tradeTools, lang, deadline, decision, run,
                () -> {
                    WakeRound.Arrivals arrivals = round.take();
                    taken.addAll(arrivals.triggers());
                    return interject(trader, arrivals, flow, tradeTools, prepared.watchHits(), unlocks, lang);
                });
        if (outcome.modelCalls() >= MAX_MODEL_CALLS) {
            // 保险丝收束不算失败（已有动作真实生效），但必须留痕——否则时间线上像正常决策
            decision.setError(prompts.get(lang, "trader.error.callLimit", Map.of("limit", MAX_MODEL_CALLS)));
        }
        return outcome.reasoning();
    }

    /**
     * 这一轮进行中到来的事成文，插进对话：到点的例行一段，每条触发一段。
     * 触发归的那个币放开开仓；盯盘命中在开场白里已经报过的不再说一遍，没报过的记进 told，这一轮成功了才算告诉过
     */
    private List<Message> interject(AiTrader trader, WakeRound.Arrivals arrivals, WakeFlows.Flow flow, TradeTools tradeTools,
                                    Set<Long> told, boolean unlocks, AgentLang lang) {
        List<Message> out = new ArrayList<>();
        if (arrivals.routineBoundary() != null) {
            out.add(new UserMessage(triggerText.routineDue(trader, arrivals.routineBoundary(), lang)));
        }
        for (WakeTrigger t : arrivals.triggers()) {
            if (t.symbol() != null) {
                flow.unlock(t.symbol());
            }
            if (t instanceof WatchTrigger hit && !told.add(hit.watch().getId())) {
                continue;
            }
            out.add(new UserMessage(triggerText.interjection(t, t.symbol() == null ? null : stateOf(trader, tradeTools, t.symbol()),
                    unlocks && t.symbol() != null, lang)));
        }
        return out;
    }

    /** 插话里带的这个币的现状。这一下没查到就不带，话照发：触发已经取出来了，不能因为它让整轮作废 */
    private static String stateOf(AiTrader trader, TradeTools tradeTools, String symbol) {
        try {
            return tradeTools.lookAt(symbol);
        } catch (Exception e) {
            log.warn("[Trader] 插话时没查到账户，这段话不带现状 traderId={} {} msg={}", trader.getId(), symbol, e.getMessage());
            return null;
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

    /** 交易工具：绑定该 trader 的 sim 子账户、可交易币与风险规格，每次唤醒 new 一个；单币轮的只认自己那个币 */
    private TradeTools tradeToolsFor(AiTrader trader, WakeScope scope, long wakeTime, long deadline,
                                     AgentLang lang, WakeFlows.Flow flow) {
        return new TradeTools(
                simTradeClient,
                trader.getSimUserId(),
                scope.tradable(),
                Set.copyOf(binanceProperties.getAllCryptoSymbols()),
                sym -> MAPPER.readTree(binanceRestClient.getPremiumIndex(sym)).path("markPrice").asDecimal(null),
                planStore,
                watchStore,
                new TradeTools.WakeCtx(trader.getId(), trader.getRoundNo(), trader.getIntervalCode(), wakeTime,
                        deadline, TraderRiskConfig.of(trader), lang, scope.symbol()),
                prompts,
                messages,
                entryContext,
                flow::openingAllowed,
                gates.of(trader.getId())
        );
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
}
