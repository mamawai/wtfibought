package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlanFactory;
import com.mawai.wiibservice.agent.trading.ops.TradingOperations;

import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_BREAKOUT;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_LEGACY_TREND;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_MR;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.currentDateTime;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.currentTimeMillis;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.findBestSignalWithPriority;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.fmt;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.fmtPrice;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.hold;

/**
 * 开仓判断编排器。
 * 策略细节在 EntryStrategy 实现里，当前类只做上下文校验、候选收集、择优和下单。
 */
@Slf4j
final class EntryDecisionEngine {

    private static final List<EntryStrategy> STRATEGIES = List.of(
            new BreakoutEntryStrategy(),
            new MeanReversionEntryStrategy(),
            new TrendContinuationEntryStrategy()
    );
    private static final EntryConfluenceGate BREAKOUT_CONFLUENCE_GATE = new BreakoutConfluenceGate();
    private static final EntryConfluenceGate MR_CONFLUENCE_GATE = new MeanReversionConfluenceGate();
    private static final EntryConfluenceGate TREND_CONFLUENCE_GATE = new TrendConfluenceGate();
    private static final EntryRiskSizingService RISK_SIZING_SERVICE = new EntryRiskSizingService();
    // 最终择优权重：只改变候选排序，不回写策略原始 score。
    // LEGACY_TREND 在近期归因里明显负 EV，压到 0.7；MR 样本不足但未证伪，给 0.9 留位。
    private static final double BREAKOUT_SELECTION_WEIGHT = 1.0;
    private static final double TREND_SELECTION_WEIGHT = 0.7;
    private static final double MR_SELECTION_WEIGHT = 0.9;

    // 同 symbol 开仓后的最短等待时间，避免短时间重复追单。
    private static final long ENTRY_COOLDOWN_MS = 10 * 60 * 1000L;

    /**
     * 开仓主流程。
     *
     * <p>职责边界：这里只负责编排，不写具体策略规则。流程顺序必须谨慎：
     * 先做冷却、数据质量、方向信号和风险状态硬过滤，再收集策略候选，
     * 再做二层共振过滤、FLAT 特殊覆盖、候选择优，最后进入风控仓位和下单。</p>
     *
     * <p>返回 OPEN_xxx 表示实际发出开仓并且交易层确认成功；其它情况返回 HOLD，
     * reason 里会带上最早挡住开仓的业务原因，方便排查是信号、策略、风控还是下单失败。</p>
     */
    DeterministicTradingExecutor.ExecutionResult evaluate(TradingDecisionContext decision) {
        String symbol = decision.symbol();
        User user = decision.user();
        MarketContext ctx = decision.market();
        TradingExecutionState state = decision.state();

        // 10min 开仓冷却
        long nowMs = currentTimeMillis(state);
        Long lastEntryMs = state.getLastEntryMs(symbol);
        if (ENTRY_COOLDOWN_MS > 0 && lastEntryMs != null) {
            long remainingMs = ENTRY_COOLDOWN_MS - (nowMs - lastEntryMs);
            if (remainingMs > 0) {
                long remainingMinutes = (remainingMs + 59_999) / 60_000;
                log.info("[EntryDecision] COOLDOWN_HOLD symbol={} remaining={} min", symbol, remainingMinutes);
                return hold("COOLDOWN_HOLD symbol=" + symbol + " remaining=" + remainingMinutes + " min");
            }
        }

        // ATR 无效则无法计算 SL/TP，直接放弃开仓
        if (ctx.atr5m == null || ctx.atr5m.signum() <= 0) {
            return hold("ATR数据缺失→无法计算止损");
        }
        // 实时成交流过期，跳过开仓
        if (ctx.qualityFlags.contains("STALE_AGG_TRADE")) {
            log.info("[QualityFlag] STALE_AGG_TRADE detected symbol={}, abstain", symbol);
            return hold("STALE_AGG_TRADE: aggTrade 数据 >30s 未更新→弃权");
        }

        LocalDateTime now = currentDateTime(nowMs);
        // 选择当前时间窗口生效的方向信号
        QuantSignalDecision bestSignal = findBestSignalWithPriority(decision.signals(), decision.forecastTime(), now);
        if (bestSignal == null || "NO_TRADE".equals(bestSignal.getDirection())) {
            return hold("无有效方向信号");
        }

        String side = bestSignal.getDirection();
        boolean isLong = "LONG".equals(side);
        if (!isLong && !"SHORT".equals(side)) {
            return hold("方向信号非法: " + side);
        }
        double confidence = bestSignal.getConfidence() != null ? bestSignal.getConfidence().doubleValue() : 0;

        String mergedRiskStatus = mergeRiskStatus(decision.forecast(), bestSignal);
        if (statusContains(mergedRiskStatus, "ALL_NO_TRADE") || statusContains(mergedRiskStatus, "NO_DATA")) {
            return hold("riskStatus=" + mergedRiskStatus + "→禁止开仓");
        }

        EntryStrategyContext strategyContext = new EntryStrategyContext(ctx, decision.profile(), side, isLong, confidence);
        // 第一层由各策略识别场景；这里做二层共振，避免单点信号直接开仓。
        CandidateSet candidateSet = applyStrategyConfluenceGates(symbol, strategyContext, collectCandidates(strategyContext));
        List<EntryStrategyCandidate> candidates = candidateSet.candidates();

        boolean overallFlat = decision.forecast() != null && "FLAT".equals(decision.forecast().getOverallDecision());
        if (overallFlat) {
            candidates = candidates.stream()
                    // FLAT不是禁止交易：横盘放行MR机会；突破必须足够强；趋势延续缺方向背景先挡掉。
                    .filter(c -> PATH_MR.equals(c.path())
                            || (PATH_BREAKOUT.equals(c.path()) && c.score() >= BreakoutEntryStrategy.STRONG_FLAT_SCORE))
                    .toList();
            if (candidates.isEmpty()) {
                return hold("overallDecision=FLAT，仅MR/强突破可覆盖；候选拒绝=" + summarizeRejects(candidateSet.rejects()));
            }
        }

        if (candidates.isEmpty()) {
            return hold("无合格策略候选: " + summarizeRejects(candidateSet.rejects()));
        }

        EntryStrategyCandidate best = chooseBest(candidates);
        log.info("[EntryDecision] 入场候选择优 symbol={} side={} best={} score={} weightedScore={} riskStatus={} candidates={} rejects={}",
                symbol, side, best.path(), fmt(best.score()), fmt(selectionScore(best)), mergedRiskStatus,
                candidates.stream().map(c -> c.path() + ":" + fmt(c.score())
                        + "->" + fmt(selectionScore(c))).toList(),
                summarizeRejects(candidateSet.rejects()));

        DeterministicTradingExecutor.ExecutionResult inner = openCandidate(
                symbol, user, decision.totalEquity(), bestSignal, ctx, best,
                riskStatusScale(mergedRiskStatus), mergedRiskStatus,
                decision.profile(), decision.tools(), decision.toggles(), state, now);
        if (inner.action().startsWith("OPEN_")) {
            state.markEntry(symbol, nowMs);
        }
        return new DeterministicTradingExecutor.ExecutionResult(
                inner.action(),
                "[Strategy-" + best.path() + "] " + inner.reasoning()
                        + (overallFlat ? flatOverrideReason(best.path()) : "")
                        + (ctx.qualityFlags.contains("LOW_CONFIDENCE") ? " [LOW_CONFIDENCE仓位减半]" : ""),
                inner.executionLog());
    }

    private String flatOverrideReason(String path) {
        if (PATH_MR.equals(path)) {
            return " [overallDecision=FLAT MR覆盖]";
        }
        if (PATH_BREAKOUT.equals(path)) {
            return " [overallDecision=FLAT强突破覆盖]";
        }
        return " [overallDecision=FLAT覆盖]";
    }

    /**
     * 执行三条入场路径的第一层筛选。
     *
     * <p>每个 EntryStrategy 只判断自己是否能产出候选，不负责共振、择优、仓位和下单。
     * 通过的候选进入下一层；拒绝原因保留下来，最终没有候选时给用户/日志看。</p>
     */
    private CandidateSet collectCandidates(EntryStrategyContext context) {
        List<EntryStrategyCandidate> candidates = new ArrayList<>();
        List<String> rejects = new ArrayList<>();
        for (EntryStrategy strategy : STRATEGIES) {
            EntryStrategyResult result = strategy.build(context);
            if (result.candidate() != null) {
                candidates.add(result.candidate());
            } else if (result.rejectReason() != null) {
                rejects.add(result.rejectReason());
            }
        }
        return new CandidateSet(candidates, rejects);
    }

    /**
     * 对第一层候选做二层共振过滤。
     *
     * <p>这里不重新打策略分，只确认候选旁证是否足够。每个策略路径会进入自己的
     * EntryConfluenceGate，命中项不足时把共振命中摘要写入 rejects，避免单点信号直接开仓。</p>
     */
    private CandidateSet applyStrategyConfluenceGates(String symbol, EntryStrategyContext context, CandidateSet input) {
        List<EntryStrategyCandidate> candidates = new ArrayList<>();
        List<String> rejects = new ArrayList<>(input.rejects());

        for (EntryStrategyCandidate candidate : input.candidates()) {
            ConfluenceGateResult gate = confluenceGate(candidate.path(), context);
            if (gate.passed()) {
                log.info("[EntryDecision] ENTRY_GATE_PASS symbol={} strategy={} side={} conf={} confluence={}/{} hits={}",
                        symbol, candidate.path(), context.side(), fmt(context.confidence()),
                        gate.score(), gate.total(), gate.hitSummary());
                candidates.add(candidate);
                continue;
            }
            rejects.add(String.format("%s: 共振不足 score=%d/%d(需>=%d) hits=%s",
                    candidate.path(), gate.score(), gate.total(), gate.required(), gate.hitSummary()));
        }
        return new CandidateSet(candidates, rejects);
    }

    /**
     * 从已通过初筛和细筛的候选中选择最终执行路径。
     *
     * <p>优先级先看加权后的 selectionScore；同分再按 pathPriority 打破平手。
     * 这个方法假设调用前 candidates 非空，空列表代表上游逻辑漏检，直接抛异常更容易暴露问题。</p>
     */
    private EntryStrategyCandidate chooseBest(List<EntryStrategyCandidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparingDouble(this::selectionScore)
                        .thenComparingInt(c -> pathPriority(c.path())))
                .orElseThrow();
    }

    /**
     * 最终择优分。
     *
     * <p>策略内部 score 表示“这个策略自己有多满足”；这里乘路径权重，表达业务上对三类路径的偏好。
     * 例如原始分相近时，突破更容易被选中，MR 需要明显更高的原始分才能反超。</p>
     */
    private double selectionScore(EntryStrategyCandidate candidate) {
        return candidate.score() * pathSelectionWeight(candidate.path());
    }

    /**
     * 把策略候选转换成真实开仓请求。
     *
     * <p>这里集中处理交易前最后一层现实约束：低波动止损底线、管理端开关、行情环境缩放、
     * 低置信度缩放、手续费后利润、最低盈亏比、最大杠杆、按风险算仓位、回撤保护和下单结果。
     * 任一约束不满足都返回 HOLD，并保留具体原因。</p>
     */
    private DeterministicTradingExecutor.ExecutionResult openCandidate(
            String symbol, User user, BigDecimal totalEquity,
            QuantSignalDecision signal, MarketContext ctx,
            EntryStrategyCandidate candidate, double riskScale, String riskStatus,
            SymbolProfile profile, TradingOperations tools, TradingRuntimeToggles toggles,
            TradingExecutionState state, LocalDateTime now) {

        EntryRiskSizingService.SizingResult sizing = RISK_SIZING_SERVICE.buildPlan(
                symbol, user, totalEquity, signal, ctx, candidate, riskScale, profile, tools, toggles);
        if (sizing.rejectReason() != null) {
            return hold(sizing.rejectReason());
        }

        EntryRiskSizingService.EntryOrderPlan plan = sizing.plan();
        String reason = buildOpenReason(candidate, ctx, plan, riskStatus);
        if (toggles.playbookExitEnabled()) {
            TradingOperations.OpenResult result = tools.openPositionWithResult(candidate.side(), plan.quantity(),
                    plan.leverage(), "MARKET", null, plan.stopLoss(), plan.takeProfit(), candidate.path());
            if (result.success() && result.positionId() != null) {
                storeExitPlan(state, result.positionId(), candidate, ctx, plan, now);
            }
            String action = result.success() ? ("OPEN_" + candidate.side()) : "HOLD";
            if (!result.success()) reason += " | 开仓失败: " + result.message();
            return new DeterministicTradingExecutor.ExecutionResult(action, reason, result.message());
        }

        String result = tools.openPosition(candidate.side(), plan.quantity(), plan.leverage(), "MARKET", null,
                plan.stopLoss(), plan.takeProfit(), candidate.path());
        String action = result.startsWith("开仓成功") ? ("OPEN_" + candidate.side()) : "HOLD";
        if (!result.startsWith("开仓成功")) reason += " | 开仓失败: " + result;
        return new DeterministicTradingExecutor.ExecutionResult(action, reason, result);
    }

    private void storeExitPlan(TradingExecutionState state, Long positionId, EntryStrategyCandidate candidate,
                               MarketContext ctx, EntryRiskSizingService.EntryOrderPlan plan, LocalDateTime now) {
        try {
            ExitPlan exitPlan = ExitPlanFactory.fromEntry(
                    candidate.path(), candidate.side(), ctx.price, plan.stopLoss(), ctx.atr5m,
                    ctx.bollPb5m, ctx.rsi5m != null ? ctx.rsi5m.doubleValue() : null,
                    ctx.maAlignment1h, ctx.maAlignment15m, now);
            state.putExitPlan(positionId, exitPlan);
        } catch (IllegalArgumentException e) {
            // 开仓已成功，不能因为内存计划失败影响交易结果；后续 Step5 会做退化恢复。
            log.warn("[EntryDecision] ExitPlan写入失败 positionId={} path={} reason={}",
                    positionId, candidate.path(), e.getMessage());
        }
    }

    /**
     * 生成开仓说明。
     *
     * <p>这个 reason 会进入执行结果和日志，必须同时包含策略解释、杠杆、数量、SL/TP、
     * 实际 ATR 倍数、最终仓位缩放、风险状态和特殊保护标记，方便事后复盘。</p>
     */
    private String buildOpenReason(EntryStrategyCandidate candidate, MarketContext ctx,
                                   EntryRiskSizingService.EntryOrderPlan plan,
                                   String riskStatus) {
        double actualSlAtrMult = plan.slDistance().doubleValue() / ctx.atr5m.doubleValue();
        double actualTpAtrMult = plan.tpDistance().doubleValue() / ctx.atr5m.doubleValue();
        String reason = String.format(
                "%s lev=%dx qty=%s SL=%s(%.1fATR) TP=%s(%.1fATR) scale=%.2f riskStatus=%s | %s",
                candidate.reason(), plan.leverage(),
                plan.quantity().setScale(4, RoundingMode.HALF_UP).toPlainString(),
                fmtPrice(plan.stopLoss()), actualSlAtrMult, fmtPrice(plan.takeProfit()), actualTpAtrMult,
                plan.effectiveScale(), riskStatus, candidate.label());
        if (plan.lowVolNote() != null) reason += " [" + plan.lowVolNote() + "]";
        if (plan.inDrawdown()) reason += " [回撤保护]";
        return reason;
    }

    /**
     * 根据策略 path 派发到对应的二层共振门。
     *
     * <p>未知 path 默认放行，保持向后兼容；正常路径只会来自 TradingDecisionSupport 定义的三个常量。</p>
     */
    private ConfluenceGateResult confluenceGate(String path, EntryStrategyContext context) {
        return switch (path) {
            case PATH_BREAKOUT -> BREAKOUT_CONFLUENCE_GATE.evaluate(context);
            case PATH_MR -> MR_CONFLUENCE_GATE.evaluate(context);
            case PATH_LEGACY_TREND -> TREND_CONFLUENCE_GATE.evaluate(context);
            default -> new ConfluenceGateResult(1, 1, 1, List.of("未知策略放行"));
        };
    }

    /**
     * 合并预测周期和方向信号上的风险状态。
     *
     * <p>两个来源都可能给出风险标签。这里做去重合并，保持原始标签文本，
     * 后续 statusContains 和 riskStatusScale 会基于这些标签决定禁开或缩仓。</p>
     */
    private String mergeRiskStatus(QuantForecastCycle forecast, QuantSignalDecision signal) {
        List<String> parts = new ArrayList<>();
        addRiskParts(parts, forecast != null ? forecast.getRiskStatus() : null);
        addRiskParts(parts, signal != null ? signal.getRiskStatus() : null);
        return parts.isEmpty() ? "NORMAL" : String.join(",", parts);
    }

    /**
     * 把逗号分隔的风险标签追加到 parts，并去掉空白和重复项。
     */
    private void addRiskParts(List<String> parts, String riskStatus) {
        if (riskStatus == null || riskStatus.isBlank()) return;
        for (String raw : riskStatus.split(",")) {
            String part = raw.trim();
            if (!part.isEmpty() && !parts.contains(part)) parts.add(part);
        }
    }

    /**
     * 判断风险状态中是否包含指定标签。
     *
     * <p>按逗号切分后做精确匹配，避免 HIGH_VOL_PENALTY 这类长标签被短词误伤。</p>
     */
    private boolean statusContains(String riskStatus, String expected) {
        if (riskStatus == null || riskStatus.isBlank()) return false;
        for (String token : riskStatus.split(",")) {
            if (expected.equals(token.trim())) return true;
        }
        return false;
    }

    /**
     * 根据风险标签计算仓位缩放系数。
     *
     * <p>多个风险可以叠乘，表达“不是完全禁止，但要更小仓位试错”。硬禁止标签已在 evaluate 前置处理。</p>
     */
    private double riskStatusScale(String riskStatus) {
        double scale = 1.0;
        if (statusContains(riskStatus, "HIGH_DISAGREEMENT")) scale *= 0.60;
        if (statusContains(riskStatus, "PARTIAL_DATA")) scale *= 0.70;
        if (statusContains(riskStatus, "CAUTIOUS")) scale *= 0.80;
        if (riskStatus != null && riskStatus.contains("DATA_PENALTY")) scale *= 0.75;
        if (riskStatus != null && riskStatus.contains("HIGH_VOL_PENALTY")) scale *= 0.80;
        return scale;
    }

    /**
     * 压缩拒绝原因，避免 HOLD reason 过长。
     *
     * <p>只展示前 4 条，保留足够排查信息，同时不让日志和接口返回被长文本淹没。</p>
     */
    private String summarizeRejects(List<String> rejects) {
        if (rejects.isEmpty()) return "无";
        return String.join("; ", rejects.stream().limit(4).toList());
    }

    /**
     * 同分候选的路径优先级。
     *
     * <p>只在加权择优分相同的时候生效：突破优先，其次趋势延续，最后均值回归。
     * 这是择优的稳定性规则，不参与策略本身打分。</p>
     */
    private int pathPriority(String path) {
        return switch (path) {
            case PATH_BREAKOUT -> 3;
            case PATH_LEGACY_TREND -> 2;
            case PATH_MR -> 1;
            default -> 0;
        };
    }

    /**
     * 最终择优时的路径权重。
     *
     * <p>权重越高，同样原始 score 下越容易胜出。未知路径保持 1.0，避免老数据或新路径被意外压低。</p>
     */
    private double pathSelectionWeight(String path) {
        return switch (path) {
            case PATH_BREAKOUT -> BREAKOUT_SELECTION_WEIGHT;
            case PATH_LEGACY_TREND -> TREND_SELECTION_WEIGHT;
            case PATH_MR -> MR_SELECTION_WEIGHT;
            default -> 1.0;
        };
    }

    private record CandidateSet(List<EntryStrategyCandidate> candidates, List<String> rejects) {
    }

}
