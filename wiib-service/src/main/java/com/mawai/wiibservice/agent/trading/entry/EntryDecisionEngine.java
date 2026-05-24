package com.mawai.wiibservice.agent.trading.entry;

import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;
import com.mawai.wiibservice.agent.trading.runtime.TradingDecisionContext;
import com.mawai.wiibservice.agent.trading.runtime.TradingExecutionState;
import com.mawai.wiibservice.agent.trading.runtime.TradingRuntimeToggles;
import com.mawai.wiibservice.agent.trading.entry.confluence.BreakoutConfluenceGate;
import com.mawai.wiibservice.agent.trading.entry.confluence.ConfluenceGateResult;
import com.mawai.wiibservice.agent.trading.entry.confluence.EntryConfluenceGate;
import com.mawai.wiibservice.agent.trading.entry.confluence.MeanReversionConfluenceGate;
import com.mawai.wiibservice.agent.trading.entry.confluence.TrendConfluenceGate;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyCandidate;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyResult;
import com.mawai.wiibservice.agent.trading.entry.strategy.BreakoutEntryStrategy;
import com.mawai.wiibservice.agent.trading.entry.strategy.EntryStrategy;
import com.mawai.wiibservice.agent.trading.entry.strategy.MaSlopeEntryStrategy;
import com.mawai.wiibservice.agent.trading.entry.strategy.MeanReversionEntryStrategy;
import com.mawai.wiibservice.agent.trading.entry.strategy.TrendContinuationEntryStrategy;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlanFactory;
import com.mawai.wiibservice.agent.trading.ops.TradingOperations;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_BREAKOUT;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_LEGACY_TREND;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MA_SLOPE;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MR;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.currentDateTime;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.currentTimeMillis;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.findReferenceSignalWithPriority;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.fmt;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.fmtPrice;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.hold;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.normalizeStrategyPath;

/**
 * 开仓判断编排器。
 * 策略细节在 EntryStrategy 实现里，当前类只做上下文校验、候选收集、择优和下单。
 */
@Slf4j
public final class EntryDecisionEngine {

    private static final List<EntryStrategy> ALL_STRATEGIES = List.of(
            new BreakoutEntryStrategy(),
            new MeanReversionEntryStrategy(),
            new TrendContinuationEntryStrategy(),
            new MaSlopeEntryStrategy()
    );
    private static final List<String> DEFAULT_ENABLED_STRATEGY_PATHS = List.of(
            PATH_BREAKOUT,
            PATH_MR,
            PATH_LEGACY_TREND
    );
    private static volatile List<String> enabledStrategyPaths = DEFAULT_ENABLED_STRATEGY_PATHS;

    private static final EntryConfluenceGate BREAKOUT_CONFLUENCE_GATE = new BreakoutConfluenceGate();
    private static final EntryConfluenceGate MR_CONFLUENCE_GATE = new MeanReversionConfluenceGate();
    private static final EntryConfluenceGate TREND_CONFLUENCE_GATE = new TrendConfluenceGate();
    private static final EntryRiskSizingService RISK_SIZING_SERVICE = new EntryRiskSizingService();
    // 最终择优权重：只改变候选排序，不回写策略原始 score。
    // LEGACY_TREND 在近期归因里明显负 EV，压到 0.7；MR 样本不足但未证伪，给 0.9 留位。
    private static final double BREAKOUT_SELECTION_WEIGHT = 1.0;
    private static final double TREND_SELECTION_WEIGHT = 0.7;
    private static final double MR_SELECTION_WEIGHT = 0.9;
    private static final double MA_SLOPE_SELECTION_WEIGHT = 0.85;
    // 不是冷却：只挡同一波信号的近距离重复下注，价格顺势走开后允许继续加仓。
    private static final BigDecimal MA_SLOPE_SCALE_IN_MIN_STEP_ATR = new BigDecimal("0.30");

    private final List<EntryStrategy> allStrategies;
    private final Supplier<List<String>> enabledStrategyPathsSupplier;

    public EntryDecisionEngine() {
        this(ALL_STRATEGIES, EntryDecisionEngine::enabledStrategyPaths);
    }

    EntryDecisionEngine(List<EntryStrategy> allStrategies, List<String> enabledStrategyPaths) {
        this(allStrategies, () -> enabledStrategyPaths);
    }

    private EntryDecisionEngine(List<EntryStrategy> allStrategies, Supplier<List<String>> enabledStrategyPathsSupplier) {
        this.allStrategies = List.copyOf(allStrategies);
        this.enabledStrategyPathsSupplier = enabledStrategyPathsSupplier;
    }

    public static void setEnabledStrategyPaths(Collection<String> paths) {
        enabledStrategyPaths = normalizeEnabledStrategyPaths(paths);
    }

    public static List<String> enabledStrategyPaths() {
        return enabledStrategyPaths;
    }

    public static List<String> defaultEnabledStrategyPaths() {
        return DEFAULT_ENABLED_STRATEGY_PATHS;
    }

    /**
     * 开仓主流程。
     *
     * <p>职责边界：这里只负责编排，不写具体策略规则。流程顺序必须谨慎：
     * 先做数据质量、方向信号和风险状态硬过滤，再收集策略候选，
     * 再做二层共振过滤、FLAT 特殊覆盖、候选择优，最后进入风控仓位和下单。</p>
     *
     * <p>返回 OPEN_xxx 表示实际发出开仓并且交易层确认成功；其它情况返回 HOLD，
     * reason 里会带上最早挡住开仓的业务原因，方便排查是信号、策略、风控还是下单失败。</p>
     */
    public DeterministicTradingExecutor.ExecutionResult evaluate(TradingDecisionContext decision) {
        String symbol = decision.symbol();
        User user = decision.user();
        MarketContext ctx = decision.market();
        TradingExecutionState state = decision.state();

        long nowMs = currentTimeMillis(state);

        // ATR 无效则无法计算 SL/TP，直接放弃开仓
        if (ctx.atr == null || ctx.atr.signum() <= 0) {
            return hold("ATR数据缺失→无法计算止损");
        }
        // 实时成交流过期，跳过开仓
        if (ctx.qualityFlags.contains("STALE_AGG_TRADE")) {
            log.info("[QualityFlag] STALE_AGG_TRADE detected symbol={}, abstain", symbol);
            return hold("STALE_AGG_TRADE: aggTrade 数据 >30s 未更新→弃权");
        }

        LocalDateTime now = currentDateTime(nowMs);
        // referenceSignal 只提供风控参数和方向缩放，NO_TRADE 不再作为策略候选硬入口。
        QuantSignalDecision referenceSignal = findReferenceSignalWithPriority(decision.signals(), decision.forecastTime(), now);
        if (referenceSignal == null) {
            return hold("无可用referenceSignal");
        }

        String referenceDirection = referenceSignal.getDirection();
        double confidence = referenceSignal.getConfidence() != null ? referenceSignal.getConfidence().doubleValue() : 0;

        String mergedRiskStatus = mergeRiskStatus(decision.forecast(), referenceSignal);
        if (statusContains(mergedRiskStatus, "ALL_NO_TRADE") || statusContains(mergedRiskStatus, "NO_DATA")) {
            return hold("riskStatus=" + mergedRiskStatus + "→禁止开仓");
        }

        // 第一层由各策略识别场景；这里做二层共振，避免单点信号直接开仓。
        CandidateSet candidateSet = applyStrategyConfluenceGates(
                symbol, decision, confidence, collectCandidates(decision, confidence, referenceDirection));
        List<EntryStrategyCandidate> candidates = candidateSet.candidates();
        List<String> rejects = new ArrayList<>(candidateSet.rejects());

        boolean overallFlat = decision.forecast() != null && "FLAT".equals(decision.forecast().getOverallDecision());
        if (overallFlat) {
            candidates = candidates.stream()
                    // FLAT不是禁止交易：横盘放行MR机会；突破必须足够强；趋势延续缺方向背景先挡掉。
                    .filter(c -> PATH_MR.equals(c.path())
                            || (PATH_BREAKOUT.equals(c.path()) && c.score() >= BreakoutEntryStrategy.STRONG_FLAT_SCORE))
                    .toList();
            if (candidates.isEmpty()) {
                return hold("overallDecision=FLAT，仅MR/强突破可覆盖；候选拒绝=" + summarizeRejects(rejects));
            }
        }

        candidates = filterPositionAwareEntryQuality(decision, candidates, rejects);

        if (candidates.isEmpty()) {
            return hold("无合格策略候选: " + summarizeRejects(rejects));
        }

        EntryStrategyCandidate best = chooseBest(candidates);
        double directionRiskScale = directionRiskScale(best.side(), referenceDirection);
        double effectiveRiskScale = riskStatusScale(mergedRiskStatus) * directionRiskScale;
        log.info("[EntryDecision] 入场候选择优 symbol={} referenceDirection={} side={} best={} score={} weightedScore={} riskStatus={} directionScale={} candidates={} rejects={}",
                symbol, referenceDirection, best.side(), best.path(), fmt(best.score()), fmt(selectionScore(best)),
                mergedRiskStatus, fmt(directionRiskScale),
                candidates.stream().map(c -> c.path() + ":" + fmt(c.score())
                        + "->" + fmt(selectionScore(c))).toList(),
                summarizeRejects(rejects));

        DeterministicTradingExecutor.ExecutionResult inner = openCandidate(
                symbol, user, decision.totalEquity(), referenceSignal, ctx, best,
                effectiveRiskScale, mergedRiskStatus,
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
     * 执行启用策略池的第一层筛选。
     *
     * <p>方向接收型老策略每轮 LONG/SHORT 各跑一次；方向自治型策略只跑一次，
     * 但候选必须自带真实 side。这里不做共振、择优、仓位和下单。</p>
     */
    private CandidateSet collectCandidates(TradingDecisionContext decision, double confidence, String referenceDirection) {
        List<EntryStrategyCandidate> candidates = new ArrayList<>();
        List<String> rejects = new ArrayList<>();

        ActiveStrategySet activeStrategySet = activeStrategySet();
        rejects.addAll(activeStrategySet.rejects());
        if (activeStrategySet.strategies().isEmpty()) {
            rejects.add("无启用入场策略");
            return new CandidateSet(candidates, rejects);
        }

        for (EntryStrategy strategy : activeStrategySet.strategies()) {
            if (strategy.kind() == EntryStrategy.StrategyKind.DIRECTION_AUTONOMOUS) {
                collectFromStrategy(strategy,
                        new EntryStrategyContext(decision.symbol(), decision.market().decisionInterval,
                                decision.market(), decision.profile(), "AUTO", false, confidence),
                        candidates, rejects);
                continue;
            }
            for (String side : receiverDispatchSides(referenceDirection)) {
                collectFromStrategy(strategy,
                        contextForSide(decision, side, confidence),
                        candidates, rejects);
            }
        }
        return new CandidateSet(candidates, rejects);
    }

    private ActiveStrategySet activeStrategySet() {
        List<String> enabledPaths = normalizeEnabledStrategyPaths(enabledStrategyPathsSupplier.get());
        List<EntryStrategy> active = new ArrayList<>();
        List<String> rejects = new ArrayList<>();
        if (enabledPaths.isEmpty()) {
            return new ActiveStrategySet(active, rejects);
        }

        for (String enabledPath : enabledPaths) {
            EntryStrategy strategy = findStrategy(enabledPath);
            if (strategy == null) {
                rejects.add("未注册策略: " + enabledPath);
                continue;
            }
            active.add(strategy);
        }
        return new ActiveStrategySet(active, rejects);
    }

    private EntryStrategy findStrategy(String path) {
        for (EntryStrategy strategy : allStrategies) {
            if (path.equals(strategy.path())) {
                return strategy;
            }
        }
        return null;
    }

    private List<String> receiverDispatchSides(String referenceDirection) {
        return "SHORT".equals(referenceDirection != null ? referenceDirection.trim().toUpperCase(Locale.ROOT) : null)
                ? List.of("SHORT", "LONG")
                : List.of("LONG", "SHORT");
    }

    private EntryStrategyContext contextForSide(TradingDecisionContext decision, String side, double confidence) {
        return new EntryStrategyContext(decision.symbol(), decision.market().decisionInterval,
                decision.market(), decision.profile(), side, "LONG".equals(side), confidence);
    }

    public static List<String> normalizeEnabledStrategyPaths(Collection<String> paths) {
        if (paths == null) {
            return DEFAULT_ENABLED_STRATEGY_PATHS;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : paths) {
            String path = normalizeStrategyPathToken(raw);
            if (!path.isBlank()) {
                normalized.add(path);
            }
        }
        return List.copyOf(normalized);
    }

    public static List<String> parseEnabledStrategyPaths(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return normalizeEnabledStrategyPaths(List.of(raw.split(",")));
    }

    private static String normalizeStrategyPathToken(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "PATH_BREAKOUT" -> PATH_BREAKOUT;
            case "PATH_MR", "MEAN_REVERSION" -> PATH_MR;
            case "PATH_LEGACY_TREND", "TREND" -> PATH_LEGACY_TREND;
            case "PATH_MA_SLOPE" -> PATH_MA_SLOPE;
            default -> value;
        };
    }

    private boolean isTradableSide(String side) {
        return "LONG".equals(side) || "SHORT".equals(side);
    }

    private void collectFromStrategy(EntryStrategy strategy, EntryStrategyContext context,
                                     List<EntryStrategyCandidate> candidates, List<String> rejects) {
        EntryStrategyResult result = strategy.build(context);
        EntryStrategyCandidate candidate = result.candidate();
        if (candidate != null) {
            if (isTradableSide(candidate.side())) {
                candidates.add(candidate);
            } else {
                rejects.add(strategy.path() + ": 候选方向非法 side=" + candidate.side());
            }
        } else if (result.rejectReason() != null) {
            rejects.add(result.rejectReason());
        }
    }

    /**
     * 对第一层候选做二层共振过滤。
     *
     * <p>这里不重新打策略分，只确认候选旁证是否足够。每个策略路径会进入自己的
     * EntryConfluenceGate，命中项不足时把共振命中摘要写入 rejects，避免单点信号直接开仓。</p>
     */
    private CandidateSet applyStrategyConfluenceGates(
            String symbol, TradingDecisionContext decision, double confidence, CandidateSet input) {
        List<EntryStrategyCandidate> candidates = new ArrayList<>();
        List<String> rejects = new ArrayList<>(input.rejects());

        for (EntryStrategyCandidate candidate : input.candidates()) {
            EntryStrategyContext gateContext = contextForSide(decision, candidate.side(), confidence);
            ConfluenceGateResult gate = confluenceGate(candidate.path(), gateContext);
            if (gate.passed()) {
                log.info("[EntryDecision] ENTRY_GATE_PASS symbol={} strategy={} side={} conf={} confluence={}/{} hits={}",
                        symbol, candidate.path(), gateContext.side(), fmt(gateContext.confidence()),
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
            BigDecimal atrAtEntry = PATH_MA_SLOPE.equals(candidate.path())
                    && ctx.atrClosed != null && ctx.atrClosed.signum() > 0 ? ctx.atrClosed : ctx.atr;
            ExitPlan exitPlan = ExitPlanFactory.fromEntry(
                    candidate.path(), candidate.side(), ctx.price, plan.stopLoss(), atrAtEntry,
                    ctx.bollPb, ctx.rsi != null ? ctx.rsi.doubleValue() : null,
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
        BigDecimal atrForReason = PATH_MA_SLOPE.equals(candidate.path())
                && ctx.atrClosed != null && ctx.atrClosed.signum() > 0 ? ctx.atrClosed : ctx.atr;
        double actualSlAtrMult = plan.slDistance().doubleValue() / atrForReason.doubleValue();
        double actualTpAtrMult = plan.tpDistance().doubleValue() / atrForReason.doubleValue();
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
     * <p>未知 path 默认放行，给方向自治新策略预留接入口；老三策略继续走原共振门。</p>
     */
    private ConfluenceGateResult confluenceGate(String path, EntryStrategyContext context) {
        return switch (path) {
            case PATH_BREAKOUT -> BREAKOUT_CONFLUENCE_GATE.evaluate(context);
            case PATH_MR -> MR_CONFLUENCE_GATE.evaluate(context);
            case PATH_LEGACY_TREND -> TREND_CONFLUENCE_GATE.evaluate(context);
            case PATH_MA_SLOPE -> new ConfluenceGateResult(1, 1, 1, List.of("MaSlope内置确认放行"));
            default -> new ConfluenceGateResult(1, 1, 1, List.of("未知策略放行"));
        };
    }

    private List<EntryStrategyCandidate> filterPositionAwareEntryQuality(
            TradingDecisionContext decision, List<EntryStrategyCandidate> candidates, List<String> rejects) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        List<EntryStrategyCandidate> accepted = new ArrayList<>();
        for (EntryStrategyCandidate candidate : candidates) {
            String reject = maSlopeScaleInQualityReject(decision, candidate);
            if (reject == null) {
                accepted.add(candidate);
            } else {
                rejects.add(reject);
            }
        }
        return accepted;
    }

    private String maSlopeScaleInQualityReject(TradingDecisionContext decision, EntryStrategyCandidate candidate) {
        if (!PATH_MA_SLOPE.equals(candidate.path())
                || decision.symbolPositions() == null || decision.symbolPositions().isEmpty()
                || decision.market() == null || decision.market().price == null
                || !hasPositiveAtrForScaleIn(decision.market())) {
            return null;
        }

        BigDecimal currentPrice = decision.market().price;
        BigDecimal requiredStep = atrForScaleIn(decision.market()).multiply(MA_SLOPE_SCALE_IN_MIN_STEP_ATR);
        BigDecimal anchorEntry = null;
        boolean candidateLong = "LONG".equals(candidate.side());
        for (FuturesPositionDTO position : decision.symbolPositions()) {
            if (!isOpenMaSlopePosition(position) || !candidate.side().equals(position.getSide())
                    || position.getEntryPrice() == null || position.getEntryPrice().signum() <= 0) {
                continue;
            }
            BigDecimal entry = position.getEntryPrice();
            if (anchorEntry == null) {
                anchorEntry = entry;
            } else if (candidateLong && entry.compareTo(anchorEntry) > 0) {
                anchorEntry = entry;
            } else if (!candidateLong && entry.compareTo(anchorEntry) < 0) {
                anchorEntry = entry;
            }
        }
        if (anchorEntry == null) {
            return null;
        }

        BigDecimal favorableMove = candidateLong
                ? currentPrice.subtract(anchorEntry)
                : anchorEntry.subtract(currentPrice);
        if (favorableMove.compareTo(requiredStep) >= 0) {
            return null;
        }
        return "MA_SLOPE: 同向加仓距离不足 move=" + fmt(favorableMove.doubleValue())
                + " < " + fmt(requiredStep.doubleValue()) + "(0.30ATR)";
    }

    private boolean isOpenMaSlopePosition(FuturesPositionDTO position) {
        return position != null
                && "OPEN".equals(position.getStatus())
                && PATH_MA_SLOPE.equals(normalizeStrategyPath(position.getMemo()));
    }

    private boolean hasPositiveAtrForScaleIn(MarketContext ctx) {
        return atrForScaleIn(ctx).signum() > 0;
    }

    private BigDecimal atrForScaleIn(MarketContext ctx) {
        if (ctx.atrClosed != null && ctx.atrClosed.signum() > 0) {
            return ctx.atrClosed;
        }
        return ctx.atr != null ? ctx.atr : BigDecimal.ZERO;
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
     * referenceDirection 只影响仓位大小，不再参与候选择优。
     */
    private double directionRiskScale(String candidateSide, String referenceDirection) {
        if (!isTradableSide(candidateSide) || referenceDirection == null || referenceDirection.isBlank()) {
            return 1.0;
        }
        String normalizedReference = referenceDirection.trim().toUpperCase(Locale.ROOT);
        if ("NO_TRADE".equals(normalizedReference)) {
            return 0.85;
        }
        if (!isTradableSide(normalizedReference)) {
            return 1.0;
        }
        return normalizedReference.equals(candidateSide) ? 1.0 : 0.85;
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
            case PATH_BREAKOUT -> 4;
            case PATH_MA_SLOPE -> 3;
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
            case PATH_MA_SLOPE -> MA_SLOPE_SELECTION_WEIGHT;
            default -> 1.0;
        };
    }

    private record ActiveStrategySet(List<EntryStrategy> strategies, List<String> rejects) {
    }

    private record CandidateSet(List<EntryStrategyCandidate> candidates, List<String> rejects) {
    }

}
