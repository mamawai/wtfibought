package com.mawai.wiibservice.agent.trading;

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
import java.util.concurrent.atomic.AtomicLong;

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

    // 同 symbol 开仓后的最短等待时间，避免短时间重复追单。
    private static final long ENTRY_COOLDOWN_MS = 10 * 60 * 1000L;
    // 单次开平仓往返手续费估算比例，用于过滤扣费后不划算的入场。
    private static final double ROUND_TRIP_FEE_RATE = 0.0008;
    // 扣除手续费后，TP 至少还要留下的 ATR 利润空间。
    private static final double MIN_PROFIT_AFTER_FEE_ATR = 0.5;
    // 单笔交易允许承担的账户权益风险比例。
    private static final BigDecimal RISK_PER_TRADE = new BigDecimal("0.02");
    // 开仓最低盈亏比要求：MR 胜率型策略单独放宽，仍保留手续费后利润检查。
    private static final BigDecimal DEFAULT_MIN_ENTRY_RR = new BigDecimal("1.2");
    private static final BigDecimal MR_MIN_ENTRY_RR = new BigDecimal("1.0");
    private static final AtomicLong RR_GUARD_TRIGGERED_COUNT = new AtomicLong();
    private static final AtomicLong MARGIN_CAP_TRIGGERED_COUNT = new AtomicLong();
    // 单笔仓位最多占用可用余额的保证金比例。
    private static final BigDecimal MAX_MARGIN_PCT = new BigDecimal("0.15");
    // 账户权益低于初始资金该比例时，认为进入回撤保护状态。
    private static final BigDecimal DRAWDOWN_THRESHOLD = new BigDecimal("0.85");
    private static final BigDecimal DRAWDOWN_REDUCTION = new BigDecimal("0.7");
    private static final double LOW_CONFIDENCE_POSITION_SCALE = 0.5;
    private static final double LOW_VOL_SL_EXPAND_MAX = 3.0;
    private static final double LOW_VOL_POSITION_SCALE = 0.6;

    private record SlTpAdjustment(BigDecimal slDistance, BigDecimal tpDistance,
                                  double positionScale, String note) {
    }

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
                    .filter(c -> PATH_BREAKOUT.equals(c.path()) && c.score() >= BreakoutEntryStrategy.STRONG_FLAT_SCORE)
                    .toList();
            if (candidates.isEmpty()) {
                return hold("overallDecision=FLAT，仅强突破可覆盖；候选拒绝=" + summarizeRejects(candidateSet.rejects()));
            }
        }

        if (candidates.isEmpty()) {
            return hold("无合格策略候选: " + summarizeRejects(candidateSet.rejects()));
        }

        EntryStrategyCandidate best = chooseBest(candidates);
        log.info("[EntryDecision] 入场候选择优 symbol={} side={} best={} score={} riskStatus={} candidates={} rejects={}",
                symbol, side, best.path(), fmt(best.score()), mergedRiskStatus,
                candidates.stream().map(c -> c.path() + ":" + fmt(c.score())).toList(),
                summarizeRejects(candidateSet.rejects()));

        DeterministicTradingExecutor.ExecutionResult inner = openCandidate(
                symbol, user, decision.totalEquity(), bestSignal, ctx, best,
                riskStatusScale(mergedRiskStatus), mergedRiskStatus,
                decision.profile(), decision.tools(), decision.toggles());
        if (inner.action().startsWith("OPEN_")) {
            state.markEntry(symbol, nowMs);
        }
        return new DeterministicTradingExecutor.ExecutionResult(
                inner.action(),
                "[Strategy-" + best.path() + "] " + inner.reasoning()
                        + (overallFlat ? " [overallDecision=FLAT强突破覆盖]" : "")
                        + (ctx.qualityFlags.contains("LOW_CONFIDENCE") ? " [LOW_CONFIDENCE仓位减半]" : ""),
                inner.executionLog());
    }

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

    private CandidateSet applyStrategyConfluenceGates(String symbol, EntryStrategyContext context, CandidateSet input) {
        List<EntryStrategyCandidate> candidates = new ArrayList<>();
        List<String> rejects = new ArrayList<>(input.rejects());

        for (EntryStrategyCandidate candidate : input.candidates()) {
            ConfluenceGate gate = confluenceGate(candidate.path(), context);
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

    private EntryStrategyCandidate chooseBest(List<EntryStrategyCandidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparingDouble(EntryStrategyCandidate::score)
                        .thenComparingInt(c -> pathPriority(c.path())))
                .orElseThrow();
    }

    private DeterministicTradingExecutor.ExecutionResult openCandidate(
            String symbol, User user, BigDecimal totalEquity,
            QuantSignalDecision signal, MarketContext ctx,
            EntryStrategyCandidate candidate, double riskScale, String riskStatus,
            SymbolProfile profile, TradingOperations tools, TradingRuntimeToggles toggles) {

        SlTpAdjustment adj = adjustForNoiseFloor(candidate.slDistance(), candidate.tpDistance(), ctx.price, profile);
        if (adj == null) {
            return hold(String.format("%s: 极端低波动 ATR/price=%.3f%% 扩SL>%.1fx→放弃",
                    candidate.label(), ctx.atr5m.doubleValue() / ctx.price.doubleValue() * 100, LOW_VOL_SL_EXPAND_MAX));
        }
        boolean isLowVol = adj.positionScale() < 1.0;
        if (isLowVol && !toggles.lowVolTradingEnabled()) {
            return hold(candidate.label() + ": " + adj.note() + " → HOLD(低波动交易被管理端关闭)");
        }

        double regimeScale = pathRegimeScale(candidate.path(), ctx, signal);
        if (regimeScale <= 0) {
            return hold(candidate.label() + ": regime=" + ctx.regime + "且信号弱→观望");
        }

        double effectiveScale = candidate.positionScale() * regimeScale * riskScale * adj.positionScale();
        if (ctx.qualityFlags.contains("LOW_CONFIDENCE")) {
            effectiveScale *= LOW_CONFIDENCE_POSITION_SCALE;
        }
        effectiveScale = Math.clamp(effectiveScale, 0.05, 1.0);

        BigDecimal slDistance = adj.slDistance();
        BigDecimal tpDistance = adj.tpDistance();
        BigDecimal price = ctx.price;
        BigDecimal stopLoss = candidate.isLong() ? price.subtract(slDistance) : price.add(slDistance);
        BigDecimal tp = candidate.isLong() ? price.add(tpDistance) : price.subtract(tpDistance);

        String feeCheck = checkFeeAwareRR(price, ctx.atr5m, slDistance, tpDistance);
        if (feeCheck != null) return hold(candidate.label() + ": " + feeCheck);
        String rrGuard = checkRiskRewardGuard(symbol, candidate.path(), slDistance, tpDistance);
        if (rrGuard != null) return hold(candidate.label() + ": " + rrGuard);

        int leverage = Math.min(signal.getMaxLeverage() != null ? signal.getMaxLeverage() : 10,
                candidate.maxLeverage());
        BigDecimal quantity = calcQuantityByRisk(user, totalEquity, price, slDistance, leverage,
                effectiveScale, signal.getMaxPositionPct());
        if (quantity == null) return hold(candidate.label() + ": 仓位计算失败(余额不足或超限)");

        if (isInDrawdown(totalEquity)) {
            leverage = Math.max(5, (int) (leverage * DRAWDOWN_REDUCTION.doubleValue()));
            quantity = quantity.multiply(DRAWDOWN_REDUCTION).setScale(8, RoundingMode.HALF_DOWN);
        }

        String reason = buildOpenReason(candidate, ctx, leverage, quantity, stopLoss, tp,
                slDistance, tpDistance, effectiveScale, riskStatus, isLowVol ? adj.note() : null, totalEquity);
        String result = tools.openPosition(candidate.side(), quantity, leverage, "MARKET", null,
                stopLoss, tp, candidate.path());
        String action = result.startsWith("开仓成功") ? ("OPEN_" + candidate.side()) : "HOLD";
        if (!result.startsWith("开仓成功")) reason += " | 开仓失败: " + result;
        return new DeterministicTradingExecutor.ExecutionResult(action, reason, result);
    }

    private String buildOpenReason(EntryStrategyCandidate candidate, MarketContext ctx,
                                   int leverage, BigDecimal quantity,
                                   BigDecimal stopLoss, BigDecimal tp,
                                   BigDecimal slDistance, BigDecimal tpDistance,
                                   double effectiveScale, String riskStatus,
                                   String lowVolNote, BigDecimal totalEquity) {
        double actualSlAtrMult = slDistance.doubleValue() / ctx.atr5m.doubleValue();
        double actualTpAtrMult = tpDistance.doubleValue() / ctx.atr5m.doubleValue();
        String reason = String.format(
                "%s lev=%dx qty=%s SL=%s(%.1fATR) TP=%s(%.1fATR) scale=%.2f riskStatus=%s | %s",
                candidate.reason(), leverage,
                quantity.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                fmtPrice(stopLoss), actualSlAtrMult, fmtPrice(tp), actualTpAtrMult,
                effectiveScale, riskStatus, candidate.label());
        if (lowVolNote != null) reason += " [" + lowVolNote + "]";
        if (isInDrawdown(totalEquity)) reason += " [回撤保护]";
        return reason;
    }

    private ConfluenceGate confluenceGate(String path, EntryStrategyContext context) {
        return switch (path) {
            case PATH_BREAKOUT -> breakoutConfluenceGate(context);
            case PATH_MR -> meanReversionConfluenceGate(context);
            case PATH_LEGACY_TREND -> trendConfluenceGate(context);
            default -> new ConfluenceGate(1, 1, 1, List.of("未知策略放行"));
        };
    }

    private ConfluenceGate trendConfluenceGate(EntryStrategyContext context) {
        MarketContext ctx = context.market();
        boolean isLong = context.isLong();
        List<String> hits = new ArrayList<>();
        addHit(hits, EntryStrategySupport.regimeSupports(ctx, isLong)
                || EntryStrategySupport.directionAligns(ctx.maAlignment1h, isLong), "大级别方向");
        addHit(hits, EntryStrategySupport.directionAligns(ctx.maAlignment15m, isLong)
                || EntryStrategySupport.directionAligns(ctx.maAlignment5m, isLong)
                || EntryStrategySupport.priceAboveEmaSupports(ctx, isLong), "中短线结构");
        addHit(hits, EntryStrategySupport.macdSupports(ctx, isLong)
                || EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong), "动能同向");
        addHit(hits, volumeAtLeast(ctx, 1.20), "量能有效");
        addHit(hits, !EntryStrategySupport.microAgainst(ctx, isLong, 0.30), "微结构未强反向");
        addHit(hits, rsiTrendHealthy(ctx, isLong), "RSI健康");
        return new ConfluenceGate(hits.size(), 6, 4, hits);
    }

    private ConfluenceGate meanReversionConfluenceGate(EntryStrategyContext context) {
        MarketContext ctx = context.market();
        boolean isLong = context.isLong();
        List<String> hits = new ArrayList<>();
        boolean deepGateStretch = bollStretchedForMeanReversion(ctx, isLong)
                && rsiStretchedForMeanReversion(ctx, isLong);
        int reversalVotes = reversalVoteCount(ctx, isLong);
        addHit(hits, bollStretchedForMeanReversion(ctx, isLong), "BB%B深度偏离");
        addHit(hits, rsiStretchedForMeanReversion(ctx, isLong), "RSI有效拉伸");
        addHit(hits, "RANGE".equals(ctx.regime) || "SQUEEZE".equals(ctx.regime)
                || "WEAKENING".equals(ctx.regimeTransition), "环境适合回归");
        addHit(hits, reversalVotes >= 2 || (deepGateStretch && reversalVotes >= 1), "反转票足够");
        addHit(hits, !EntryStrategySupport.microAgainst(ctx, isLong, 0.30), "微结构未强反向");
        addHit(hits, !hardTrendAgainst(ctx, isLong)
                || (deepGateStretch && "WEAKENING".equals(ctx.regimeTransition)), "无强趋势推进");
        return new ConfluenceGate(hits.size(), 6, 4, hits);
    }

    private ConfluenceGate breakoutConfluenceGate(EntryStrategyContext context) {
        MarketContext ctx = context.market();
        boolean isLong = context.isLong();
        List<String> hits = new ArrayList<>();
        addHit(hits, bollNearBreakoutBand(ctx, isLong), "强突破位置");
        addHit(hits, volumeAtLeast(ctx, 1.25), "量能放大");
        addHit(hits, EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong)
                || EntryStrategySupport.macdSupports(ctx, isLong), "突破动能同向");
        addHit(hits, ctx.bollSqueeze || volumeAtLeast(ctx, 1.45), "压缩/释放环境");
        addHit(hits, ctx.maAlignment15m != null
                && !EntryStrategySupport.directionConflicts(ctx.maAlignment15m, isLong), "中周期不反向");
        addHit(hits, !EntryStrategySupport.microAgainst(ctx, isLong, 0.30), "微结构未强反向");
        return new ConfluenceGate(hits.size(), 6, 4, hits);
    }

    private void addHit(List<String> hits, boolean condition, String label) {
        if (condition) hits.add(label);
    }

    private boolean volumeAtLeast(MarketContext ctx, double threshold) {
        return ctx.volumeRatio5m != null && ctx.volumeRatio5m >= threshold;
    }

    private boolean rsiTrendHealthy(MarketContext ctx, boolean isLong) {
        if (ctx.rsi5m == null) return false;
        double rsi = ctx.rsi5m.doubleValue();
        return isLong ? rsi >= 42.0 && rsi < 78.0 : rsi > 22.0 && rsi <= 58.0;
    }

    private boolean bollStretchedForMeanReversion(MarketContext ctx, boolean isLong) {
        if (ctx.bollPb5m == null) return false;
        return isLong ? ctx.bollPb5m <= 18.0 : ctx.bollPb5m >= 82.0;
    }

    private boolean rsiStretchedForMeanReversion(MarketContext ctx, boolean isLong) {
        if (ctx.rsi5m == null) return false;
        double rsi = ctx.rsi5m.doubleValue();
        return isLong ? rsi <= 42.0 : rsi >= 58.0;
    }

    private boolean bollNearBreakoutBand(MarketContext ctx, boolean isLong) {
        if (ctx.bollPb5m == null) return false;
        return isLong ? ctx.bollPb5m >= 90.0 : ctx.bollPb5m <= 10.0;
    }

    private boolean hardTrendAgainst(MarketContext ctx, boolean isLong) {
        return EntryStrategySupport.directionConflicts(ctx.maAlignment1h, isLong)
                && EntryStrategySupport.directionConflicts(ctx.maAlignment15m, isLong)
                && EntryStrategySupport.isMacdHistAgainst(ctx.macdHistTrend5m, isLong);
    }

    private int reversalVoteCount(MarketContext ctx, boolean isLong) {
        int votes = 0;
        if (EntryStrategySupport.macdSupports(ctx, isLong)) votes++;
        if (EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong)) votes++;
        if (EntryStrategySupport.microSupports(ctx, isLong)) votes++;
        if ("WEAKENING".equals(ctx.regimeTransition)) votes++;
        return votes;
    }

    private double pathRegimeScale(String path, MarketContext ctx, QuantSignalDecision signal) {
        double confidence = signal.getConfidence() != null ? signal.getConfidence().doubleValue() : 0;
        if ("SHOCK".equals(ctx.regime)) {
            return confidence >= 0.70 ? 0.50 : 0.0;
        }
        if ("SQUEEZE".equals(ctx.regime)) {
            return PATH_BREAKOUT.equals(path) ? 0.85 : 0.65;
        }
        return 1.0;
    }

    private String mergeRiskStatus(QuantForecastCycle forecast, QuantSignalDecision signal) {
        List<String> parts = new ArrayList<>();
        addRiskParts(parts, forecast != null ? forecast.getRiskStatus() : null);
        addRiskParts(parts, signal != null ? signal.getRiskStatus() : null);
        return parts.isEmpty() ? "NORMAL" : String.join(",", parts);
    }

    private void addRiskParts(List<String> parts, String riskStatus) {
        if (riskStatus == null || riskStatus.isBlank()) return;
        for (String raw : riskStatus.split(",")) {
            String part = raw.trim();
            if (!part.isEmpty() && !parts.contains(part)) parts.add(part);
        }
    }

    private boolean statusContains(String riskStatus, String expected) {
        if (riskStatus == null || riskStatus.isBlank()) return false;
        for (String token : riskStatus.split(",")) {
            if (expected.equals(token.trim())) return true;
        }
        return false;
    }

    private double riskStatusScale(String riskStatus) {
        double scale = 1.0;
        if (statusContains(riskStatus, "HIGH_DISAGREEMENT")) scale *= 0.60;
        if (statusContains(riskStatus, "PARTIAL_DATA")) scale *= 0.70;
        if (statusContains(riskStatus, "CAUTIOUS")) scale *= 0.80;
        if (riskStatus != null && riskStatus.contains("DATA_PENALTY")) scale *= 0.75;
        if (riskStatus != null && riskStatus.contains("HIGH_VOL_PENALTY")) scale *= 0.80;
        return scale;
    }

    private String checkFeeAwareRR(BigDecimal price, BigDecimal atr,
                                   BigDecimal slDistance, BigDecimal tpDistance) {
        if (atr.signum() <= 0) return null;
        double feeAbsolute = price.doubleValue() * ROUND_TRIP_FEE_RATE;
        double feeInAtr = feeAbsolute / atr.doubleValue();
        double tpInAtr = tpDistance.doubleValue() / atr.doubleValue();
        double minTpRequired = feeInAtr + MIN_PROFIT_AFTER_FEE_ATR;

        if (tpInAtr < minTpRequired) {
            return String.format("R:R不划算: TP=%.1fATR < 手续费%.2fATR+最低利润%.1fATR=%.2fATR",
                    tpInAtr, feeInAtr, MIN_PROFIT_AFTER_FEE_ATR, minTpRequired);
        }
        return null;
    }

    private String checkRiskRewardGuard(String symbol, String strategy,
                                        BigDecimal slDistance, BigDecimal tpDistance) {
        BigDecimal minEntryRr = PATH_MR.equals(strategy) ? MR_MIN_ENTRY_RR : DEFAULT_MIN_ENTRY_RR;
        BigDecimal rr = tpDistance.divide(slDistance, 4, RoundingMode.HALF_UP);
        if (rr.compareTo(minEntryRr) >= 0) {
            return null;
        }
        long count = RR_GUARD_TRIGGERED_COUNT.incrementAndGet();
        log.info("[EntryDecision] RR_GUARD_TRIGGERED symbol={} strategy={} rr={} slDistance={} tpDistance={} count={}",
                symbol, strategy, rr.toPlainString(), slDistance.toPlainString(), tpDistance.toPlainString(), count);
        return "RR_GUARD rr=" + rr.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " <" + minEntryRr.toPlainString() + " abstain";
    }

    private SlTpAdjustment adjustForNoiseFloor(
            BigDecimal slRaw, BigDecimal tpRaw, BigDecimal price, SymbolProfile profile) {
        double rawSlDist = slRaw.doubleValue();
        double minSlDist = profile.slMinPct() * price.doubleValue();
        if (rawSlDist >= minSlDist) {
            return new SlTpAdjustment(slRaw, tpRaw, 1.0, "正常波动");
        }
        double expandRatio = minSlDist / rawSlDist;
        if (expandRatio > LOW_VOL_SL_EXPAND_MAX) {
            return null;
        }
        BigDecimal ratio = BigDecimal.valueOf(expandRatio);
        BigDecimal newSl = slRaw.multiply(ratio).setScale(8, RoundingMode.HALF_UP);
        BigDecimal newTp = tpRaw.multiply(ratio).setScale(8, RoundingMode.HALF_UP);
        return new SlTpAdjustment(newSl, newTp, LOW_VOL_POSITION_SCALE,
                String.format("低波动小仓位(扩%.2fx)", expandRatio));
    }

    private BigDecimal calcQuantityByRisk(User user, BigDecimal totalEquity,
                                          BigDecimal price,
                                          BigDecimal slDistance, int leverage,
                                          double scale,
                                          BigDecimal maxPositionPct) {
        BigDecimal balance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal frozen = user.getFrozenBalance() != null ? user.getFrozenBalance() : BigDecimal.ZERO;
        BigDecimal equity = totalEquity != null ? totalEquity : balance.add(frozen);
        BigDecimal quantity = equity.multiply(RISK_PER_TRADE)
                .multiply(BigDecimal.valueOf(scale))
                .divide(slDistance, 8, RoundingMode.HALF_DOWN);
        if (quantity.signum() <= 0) return null;

        if (maxPositionPct != null && maxPositionPct.signum() > 0) {
            BigDecimal maxPctQuantity = equity.multiply(maxPositionPct).divide(price, 8, RoundingMode.DOWN);
            if (maxPctQuantity.signum() <= 0) return null;
            if (quantity.compareTo(maxPctQuantity) > 0) quantity = maxPctQuantity;
        }

        BigDecimal margin = quantity.multiply(price)
                .divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal maxMargin = balance.multiply(MAX_MARGIN_PCT);
        if (margin.compareTo(maxMargin) > 0) {
            BigDecimal marginBudget = maxMargin.subtract(new BigDecimal("0.01")).max(BigDecimal.ZERO);
            BigDecimal maxMarginQuantity = marginBudget.multiply(BigDecimal.valueOf(leverage))
                    .divide(price, 8, RoundingMode.DOWN);
            BigDecimal cappedMargin = maxMarginQuantity.multiply(price)
                    .divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
            long count = MARGIN_CAP_TRIGGERED_COUNT.incrementAndGet();
            if (maxMarginQuantity.signum() <= 0 || cappedMargin.compareTo(maxMargin) > 0) {
                log.info("[EntryDecision] MARGIN_CAP_TRIGGERED action=hold margin={} maxMargin={} reducedMargin={} price={} leverage={} count={}",
                        margin.toPlainString(), maxMargin.toPlainString(), cappedMargin.toPlainString(),
                        price.toPlainString(), leverage, count);
                return null;
            }
            log.info("[EntryDecision] MARGIN_CAP_TRIGGERED action=cap_to_max_margin margin={} maxMargin={} cappedMargin={} price={} leverage={} count={}",
                    margin.toPlainString(), maxMargin.toPlainString(), cappedMargin.toPlainString(),
                    price.toPlainString(), leverage, count);
            quantity = maxMarginQuantity;
        }

        return quantity.signum() > 0 ? quantity : null;
    }

    private boolean isInDrawdown(BigDecimal totalEquity) {
        if (totalEquity == null) return false;
        return totalEquity.compareTo(DeterministicTradingExecutor.INITIAL_BALANCE.multiply(DRAWDOWN_THRESHOLD)) < 0;
    }

    private String summarizeRejects(List<String> rejects) {
        if (rejects.isEmpty()) return "无";
        return String.join("; ", rejects.stream().limit(4).toList());
    }

    private int pathPriority(String path) {
        return switch (path) {
            case PATH_BREAKOUT -> 3;
            case PATH_LEGACY_TREND -> 2;
            case PATH_MR -> 1;
            default -> 0;
        };
    }

    private record CandidateSet(List<EntryStrategyCandidate> candidates, List<String> rejects) {
    }

    private record ConfluenceGate(int score, int total, int required, List<String> hits) {

        boolean passed() {
            return score >= required;
        }

        String hitSummary() {
            return hits.isEmpty() ? "无" : String.join("/", hits);
        }
    }
}
