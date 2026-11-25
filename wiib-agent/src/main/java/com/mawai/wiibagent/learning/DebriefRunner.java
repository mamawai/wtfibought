package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.BoundedRuns;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.ToolCallTraceHook;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.wakeup.WakeTrace;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 单笔复盘：一笔单子平仓满 24 小时后自动做，主人也可以提前点（{@link #manualDue}）；一个带工具的 ReAct 会话只判这一笔，落一行 DEBRIEF。
 * 代码备开场素材（{@link DebriefMaterialAssembler}），模型用只读工具查当时的情况，输出【单笔复盘】正文和【归类】行。
 * 这里不写账本：归类行留在正文里，统一复盘时由代码合并落账。
 * <p>
 * 失败语义：超时、模型报错、输出为空、没有【归类】段都记 ERROR 行，过后自动重试（见 {@link #attemptable}）；
 * 自动试满还失败的不再自动做，主人还能手动点，复盘成功了照样进账本。失败不计连败。过程不上现场流，只落 trace_json。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DebriefRunner {

    /** 平仓后等多久才复盘：出场后 24 小时的走势到这时才齐 */
    static final long WAIT_MS = 24 * 3_600_000L;
    /** 平仓超过这么久的不再补做：复盘开关关了很久再打开、停机很久再起来，不会把整局的老单子全翻出来做一遍 */
    static final long MAX_AGE_MS = 7 * 24 * 3_600_000L;
    static final int MAX_MODEL_CALLS = 16;
    /** 一笔最多跑多久：开场素材十几万 token，一次模型调用就要几分钟 */
    static final int TIMEOUT_SECONDS = 1800;
    /** 失败后隔多久再试：第一次失败等 1 小时，第二次失败等 24 小时，第三次还失败就不再试 */
    static final long[] RETRY_DELAYS_MS = {3_600_000L, 24 * 3_600_000L};

    private final DebriefMaterialAssembler assembler;
    private final DebriefToolkit debriefToolkit;
    private final IndicatorToolkit indicatorToolkit;
    private final NewsToolkit newsToolkit;
    private final LocalizedToolCallbacks localizedTools;
    private final TraderModelFactory modelFactory;
    private final AiTraderDecisionMapper decisionMapper;
    private final AiTraderPlanMapper planMapper;
    private final SimTradeClient simTradeClient;
    private final PromptCatalog prompts;
    private final UserLangResolver userLangResolver;
    private final ExperienceOpParser parser;
    private final RunningDebriefs running;
    private final TraderPlanStore planStore;
    /** 限时跑会话：到点或关停叫停 */
    private final BoundedRuns runs;

    /** 总时长注入点：测试把它调短 */
    int timeoutSeconds = TIMEOUT_SECONDS;

    /** 到期的一笔：sim 的已平仓位，和绑着它的计划行 */
    public record Due(FuturesPositionDTO position, AiTraderPlan plan) {
    }

    /**
     * 现在该复盘的一笔，没有返回 null。候选是绑了仓位、已归档、主人没忽略、还没进过统一复盘的计划行；
     * 平仓满 24 小时（见 {@link #ready}）、不超过 7 天、还能再试（见 {@link #attemptable}）的里面取平仓最早的。
     * 平仓时刻以 sim 的已平仓位为准
     */
    public Due next(AiTrader trader, long nowMs) {
        long oldest = nowMs - MAX_AGE_MS;
        Map<Long, AiTraderPlan> planByPos = new LinkedHashMap<>();
        // 归档不会早于平仓：归档时刻都超过 7 天的，平仓更早，这里先筛掉，不用为它们去问 sim
        for (AiTraderPlan p : planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, trader.getId())
                .eq(AiTraderPlan::getRoundNo, trader.getRoundNo())
                .eq(AiTraderPlan::getStatus, AiTraderPlan.STATUS_CLOSED)
                .eq(AiTraderPlan::getStale, false)
                .isNotNull(AiTraderPlan::getPositionId)
                .isNull(AiTraderPlan::getReviewId)
                .ge(AiTraderPlan::getClosedWakeTime, oldest))) {
            planByPos.putIfAbsent(p.getPositionId(), p);
        }
        if (planByPos.isEmpty()) {
            return null;
        }
        // 复盘成功过的先去掉，剩下有候选才去问 sim；重试次数和间隔要拿平仓时刻算到期，问过 sim 再判。
        // 被服务更新打断的不算尝试
        Map<Long, List<AiTraderDecision>> attempts = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                        .select(AiTraderDecision::getPositionId, AiTraderDecision::getStatus, AiTraderDecision::getWakeTime,
                                AiTraderDecision::getError)
                        .eq(AiTraderDecision::getTraderId, trader.getId())
                        .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                        .eq(AiTraderDecision::getKind, AiTraderDecision.KIND_DEBRIEF)
                        .in(AiTraderDecision::getPositionId, planByPos.keySet()))
                .stream().filter(d -> !interrupted(d)).collect(Collectors.groupingBy(AiTraderDecision::getPositionId));
        planByPos.keySet().removeIf(id -> attempts.getOrDefault(id, List.of()).stream()
                .anyMatch(d -> AiTraderDecision.STATUS_OK.equals(d.getStatus())));
        if (planByPos.isEmpty()) {
            return null;
        }
        return simTradeClient.getClosedPositions(trader.getSimUserId(), PlayStatsAssembler.FETCH_LIMIT).stream()
                .filter(p -> planByPos.containsKey(p.getId()))
                .filter(p -> {
                    long closeMs = TradePairing.msOf(p.getUpdatedAt());
                    return closeMs >= oldest && ready(closeMs, nowMs)
                            && attemptable(attempts.getOrDefault(p.getId(), List.of()), dueAt(closeMs), nowMs);
                })
                .min(Comparator.comparing(FuturesPositionDTO::getUpdatedAt))
                .map(p -> new Due(p, planByPos.get(p.getId())))
                .orElse(null);
    }

    /** 这一行是被服务更新打断的：error 是哪门语言的"被打断"文案都认 */
    private boolean interrupted(AiTraderDecision d) {
        return Arrays.stream(AgentLang.values())
                .anyMatch(lang -> prompts.get(lang, "debrief.error.interrupted").equals(d.getError()));
    }

    /**
     * 自动复盘的到期时刻，分两段算：
     * <ol>
     * <li>平仓落在哪根 5m 里，就从那根的开盘往后数 24 小时。事实表要看到平仓后 24 小时的走势，
     *     最后要用的那一根 K 线正好在这个时刻收盘。</li>
     * <li>再多等一根 5m。扫描是"任意一个币的 5m 收盘"触发的：BTC 的收盘事件先到，
     *     这笔单子的 SOL 那一根可能还没写进库，这时去算事实表会少最后一根。
     *     多等一根，那一根肯定已经在库里了。</li>
     * </ol>
     * 例：14:02 平仓，落在 14:00 开盘的那根 → 最后一根在第二天 14:00 收盘 → 第二天 14:05 的扫描才做它。
     */
    static long dueAt(long closeMs) {
        long closeBar = closeMs - Math.floorMod(closeMs, CaseFileBuilder.BAR_MS);
        return closeBar + WAIT_MS + CaseFileBuilder.BAR_MS;
    }

    /** 这笔到没到自动复盘的时刻（{@link #dueAt}） */
    static boolean ready(long closeMs, long nowMs) {
        return nowMs >= dueAt(closeMs);
    }

    /**
     * 手动复盘能不能做了：平仓那根 5m 收盘后再过一根。
     * 和 {@link #dueAt} 的第 2 条一样，多等一根，平仓那根肯定已经在库里，事实表不会少它
     */
    public static boolean manualReady(long closeMs, long nowMs) {
        long closeBar = closeMs - Math.floorMod(closeMs, CaseFileBuilder.BAR_MS);
        return nowMs >= closeBar + 2 * CaseFileBuilder.BAR_MS;
    }

    /**
     * 没复盘成功过的这笔，现在还能不能自动再跑：到期之后的失败没试满，离上一次失败够久。
     * 到期之前主人手动点失败的不算次数
     */
    static boolean attemptable(List<AiTraderDecision> attempts, long dueMs, long nowMs) {
        List<Long> failed = attempts.stream().map(AiTraderDecision::getWakeTime).filter(t -> t >= dueMs).toList();
        if (failed.isEmpty()) {
            return true;
        }
        if (failed.size() > RETRY_DELAYS_MS.length) {
            return false;
        }
        long lastFailed = failed.stream().mapToLong(Long::longValue).max().orElseThrow();
        return nowMs >= lastFailed + RETRY_DELAYS_MS[failed.size() - 1];
    }

    /** 手动复盘的准入结果：能做给 due；不能做给 refusal，是消息词表 trader.debrief.* 的 key */
    public record ManualDue(Due due, String refusal) {
    }

    /**
     * 主人点"立即复盘"的那一笔：本局 sim 已平仓位里有它、平仓那根 K 线已经落库（{@link #manualReady}）、
     * 有计划行绑着它、主人没忽略、还没复盘成功过。不看自动复盘的重试次数、间隔和 7 天上限。
     * 计划还没归档（下一轮唤醒才归档）也放行：各档记的是上次唤醒对账时的状态，这里按 sim 的订单盖一遍，只改这一份、不写库
     */
    public ManualDue manualDue(AiTrader trader, long positionId, long nowMs) {
        FuturesPositionDTO pos = simTradeClient.getClosedPositions(trader.getSimUserId(), PlayStatsAssembler.FETCH_LIMIT).stream()
                .filter(p -> p.getId() == positionId).findFirst().orElse(null);
        if (pos == null) {
            return new ManualDue(null, "trader.debrief.notFound");
        }
        if (!manualReady(TradePairing.msOf(pos.getUpdatedAt()), nowMs)) {
            return new ManualDue(null, "trader.debrief.tooEarly");
        }
        AiTraderPlan plan = planMapper.selectOne(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, trader.getId())
                .eq(AiTraderPlan::getRoundNo, trader.getRoundNo())
                .eq(AiTraderPlan::getPositionId, positionId)
                .orderByAsc(AiTraderPlan::getId)
                .last("LIMIT 1"));
        if (plan == null) {
            return new ManualDue(null, "trader.debrief.noPlan");
        }
        if (Boolean.TRUE.equals(plan.getStale())) {
            return new ManualDue(null, "trader.debrief.stale");
        }
        // 失败过的不挡，只有复盘成功过的才算做过
        boolean done = decisionMapper.selectCount(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .eq(AiTraderDecision::getKind, AiTraderDecision.KIND_DEBRIEF)
                .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                .eq(AiTraderDecision::getPositionId, positionId)) > 0;
        if (done) {
            return new ManualDue(null, "trader.debrief.done");
        }
        if (AiTraderPlan.STATUS_LIVE.equals(plan.getStatus())) {
            planStore.overlayEntries(List.of(plan), Set.of(),
                    group -> simTradeClient.getGroupOrders(trader.getSimUserId(), group), userLangResolver.of(trader.getUserId()));
        }
        return new ManualDue(new Due(pos, plan), null);
    }

    /** 复盘一笔，成败都落一行 DEBRIEF；跑的时候记着这个交易员正在复盘哪个仓位 */
    public void debrief(AiTrader trader, Due due) {
        running.start(trader.getId(), due.position().getId());
        try {
            AiTraderDecision d = debriefRow(trader, due);
            decisionMapper.insert(d);
            log.info("[Debrief] 单笔复盘落行 traderId={} positionId={} status={} 模型调用{}次 工具{}次",
                    trader.getId(), d.getPositionId(), d.getStatus(), d.getModelCalls(), d.getToolCalls());
        } finally {
            running.end(trader.getId());
        }
    }

    /** 跑一笔单笔复盘，给出要落的那一行，这里不落库（wake_time 是开跑的时刻） */
    AiTraderDecision debriefRow(AiTrader trader, Due due) {
        long start = System.currentTimeMillis();
        // 语言查一次用到底：素材段名、系统提示、段标记必须是同一门
        AgentLang lang = userLangResolver.of(trader.getUserId());
        FuturesPositionDTO pos = due.position();
        AiTraderDecision d = new AiTraderDecision();
        d.setTraderId(trader.getId());
        d.setRoundNo(trader.getRoundNo());
        d.setWakeTime(start);
        d.setIntervalCode(trader.getIntervalCode());
        d.setKind(AiTraderDecision.KIND_DEBRIEF);
        d.setPositionId(pos.getId());
        d.setToolCalls(0);
        // 复盘和唤醒会同时跑，不走现场流，轨迹自己拿着
        WakeTrace trace = new WakeTrace(trader.getId(), AiTraderDecision.KIND_DEBRIEF, null, start, timeoutSeconds, null, 0, 0);
        try {
            DebriefMaterialAssembler.DebriefMaterial material = assembler.assemble(trader, pos, due.plan(), lang);
            String output = run(trader, prompts.get(lang, "debrief.system"), userPrompt(trader, pos, material, start, lang),
                    lang, d, trace, start + timeoutSeconds * 1000L);
            if (output == null) {
                d.setStatus(AiTraderDecision.STATUS_ERROR);
                d.setError(prompts.get(lang, "debrief.error.emptyOutput"));
            } else {
                d.setReasoning(output.strip());
                // 没有【归类】段，或者账本有现役条目、段里却一行都认不出：正文照存，记成失败，过后重试
                List<ExperienceLedger.Classify> rows = parser.debriefClassifies(output);
                String error = rows == null ? "debrief.error.noClassify"
                        : rows.isEmpty() && material.ledgerEntries() > 0 ? "debrief.error.noClassifyRows" : null;
                d.setStatus(error == null ? AiTraderDecision.STATUS_OK : AiTraderDecision.STATUS_ERROR);
                if (error != null) {
                    d.setError(prompts.get(lang, error));
                }
            }
        } catch (Exception e) {
            Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            // ERROR 行公开上时间线：只存归类文案，原文进日志
            d.setStatus(AiTraderDecision.STATUS_ERROR);
            d.setError(t instanceof BoundedRuns.CutException ? prompts.get(lang, "debrief.error.interrupted")
                    : t instanceof TimeoutException
                    ? prompts.get(lang, "debrief.error.timeout", Map.of("seconds", timeoutSeconds))
                    : LlmErrorMessages.classify(t, prompts, lang));
            if (t instanceof BoundedRuns.CutException) {
                log.info("[Debrief] 服务关停，单笔复盘提前收尾 traderId={} positionId={}", trader.getId(), pos.getId());
            } else {
                log.warn("[Debrief] 单笔复盘失败 traderId={} positionId={} msg={}", trader.getId(), pos.getId(), d.getError(), t);
            }
        }
        d.setLatencyMs((int) (System.currentTimeMillis() - start));
        trace.end(d.getStatus(), d.getError(), null, d.getLatencyMs(), d.getModelCalls(), d.getTotalTokens());
        d.setTraceJson(trace.toJson());
        return d;
    }

    /**
     * 限时跑一个 ReAct 会话，返回最后一条有正文的回复，没有返回 null。
     * 工具调用和用量落在 finally：超时作废的那次，调过的工具和烧掉的 token 也是真的
     */
    private String run(AiTrader trader, String system, String user, AgentLang lang, AiTraderDecision d,
                       WakeTrace trace, long deadline) throws Exception {
        UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
        ToolCallTraceHook calls = new ToolCallTraceHook();
        // 只挂能查历史时刻的：轮次详情、历史单子、带 endTime 的 K 线三件套、带起止时刻的快讯存档
        LlmClient client = LlmClient.builder().model(model).systemPrompt(system)
                .tools(localizedTools.of(lang, debriefToolkit.boundTo(trader, lang), indicatorToolkit,
                        newsToolkit.archiveFor(lang)))
                .build();
        String notExecuted = prompts.get(lang, "llm.callLimit.notExecuted");
        ReactLoop loop = ReactLoop.builder().client(client)
                .limiter(new ModelCallLimiter(MAX_MODEL_CALLS, notExecuted, prompts.get(lang, "llm.callLimit.lastCall")))
                .trace(calls)
                .build();
        trace.prompt(system, user);
        // 停止信号交给循环：到点或叫停时完成它，在途模型流不再往下烧
        BoundedRuns.Work<ReactLoop.Result> session = stop -> {
            trace.callStart();
            return loop.run(List.of(new UserMessage(user)), null, stop, new ReactLoop.Listener() {
                @Override
                public void message(Message m) {
                    if (m instanceof AssistantMessage assistant) {
                        trace.callEnd(assistant.getText(), assistant.getToolCalls());
                    } else if (m instanceof ToolResponseMessage responses) {
                        for (ToolResponseMessage.ToolResponse r : responses.getResponses()) {
                            // 回的是保险丝那句占位：这次调用没执行
                            if (notExecuted.equals(r.responseData())) {
                                trace.toolSkipped(r.id(), r.name(), r.responseData());
                            } else {
                                trace.toolResult(r.id(), r.name(), r.responseData());
                            }
                        }
                        trace.callStart();
                    }
                }
            });
        };
        try {
            // 叫停之后不等线程停下
            ReactLoop.Result result = runs.run("debrief traderId=" + trader.getId() + " positionId=" + d.getPositionId(),
                    session, deadline, Duration.ZERO);
            String output = null;
            for (Message m : result.messages()) {
                if (m instanceof AssistantMessage assistant && assistant.getText() != null && !assistant.getText().isBlank()) {
                    output = assistant.getText();
                }
            }
            return output;
        } finally {
            d.setActionsJson(MAPPER.writeValueAsString(calls.calls()));
            d.setToolCalls(calls.calls().size());
            UsageTrackingChatModel.UsageSnapshot usage = model.snapshot();
            d.setModelCalls(usage.modelCalls());
            d.setPromptTokens(usage.promptTokens());
            d.setCompletionTokens(usage.completionTokens());
            d.setTotalTokens(usage.totalTokens());
            d.setCachedTokens(usage.cachedTokens());
        }
    }

    /** 单笔复盘的用户消息：复盘对象（带平仓后过了多久） + 主人指令 + 事实表 + 各轮决策 + 账本 + 收尾 */
    String userPrompt(AiTrader trader, FuturesPositionDTO pos, DebriefMaterialAssembler.DebriefMaterial m,
                      long nowMs, AgentLang lang) {
        long closeMs = TradePairing.msOf(pos.getUpdatedAt());
        StringBuilder sb = new StringBuilder();
        sb.append(prompts.get(lang, "debrief.label.subject", Map.of(
                "id", pos.getId(), "symbol", pos.getSymbol(), "side", pos.getSide(),
                "opened", BeijingTime.format(TradePairing.msOf(pos.getCreatedAt())),
                "closed", BeijingTime.format(closeMs),
                "since", CaseFileRenderer.humanize(prompts, nowMs - closeMs, lang),
                "now", BeijingTime.format(nowMs)))).append("\n\n");
        // 主人亲笔原样注入不翻译
        sb.append(prompts.get(lang, "debrief.label.ownerInstructions")).append('\n');
        sb.append(trader.getCustomPrompt() == null || trader.getCustomPrompt().isBlank()
                ? prompts.get(lang, "debrief.label.ownerInstructionsEmpty") : trader.getCustomPrompt()).append("\n\n");
        sb.append(m.factsBlock()).append('\n');
        sb.append(m.roundsBlock()).append('\n');
        sb.append(m.ledgerBlock()).append('\n');
        sb.append(prompts.get(lang, "debrief.label.closing"));
        // 输出语言硬收尾：用户消息最末一行，排在素材与账本之后——它们可能是另一门语言
        sb.append('\n').append(prompts.get(lang, "debrief.label.outputLanguage"));
        return sb.toString();
    }
}
