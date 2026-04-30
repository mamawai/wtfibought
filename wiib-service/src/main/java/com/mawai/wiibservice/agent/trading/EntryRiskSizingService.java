package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicLong;

import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_BREAKOUT;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_MR;

/**
 * 入场前最后一层风险和仓位计算。
 *
 * <p>这个类只负责把策略候选转换成可下单的仓位计划；真正下单仍由 EntryDecisionEngine 编排。</p>
 */
@Slf4j
final class EntryRiskSizingService {

    // 单次开平仓往返手续费估算比例，用于过滤扣费后不划算的入场。
    private static final double ROUND_TRIP_FEE_RATE = 0.0008;
    // 仓位风险按“触发SL时还要付平仓手续费”估算；开仓手续费由交易服务实际扣款。
    private static final double ESTIMATED_CLOSE_FEE_RATE = ROUND_TRIP_FEE_RATE / 2.0;
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
    // 账户权益低于峰值权益该比例时，认为进入回撤保护状态。
    private static final BigDecimal DRAWDOWN_THRESHOLD = new BigDecimal("0.85");
    private static final BigDecimal DRAWDOWN_REDUCTION = new BigDecimal("0.7");
    private static final double LOW_CONFIDENCE_POSITION_SCALE = 0.5;
    private static final double LOW_VOL_SL_EXPAND_MAX = 3.0;
    private static final double LOW_VOL_POSITION_SCALE = 0.6;
    // 开仓前估算强平距离，SL 最多只能吃掉其中 80%，给 mark price 触发和滑点留缓冲。
    private static final double MAINTENANCE_MARGIN_RATE = 0.005;
    private static final double MAX_SL_TO_LIQ_DISTANCE_RATIO = 0.80;

    /**
     * 生成完整开仓计划。
     *
     * <p>顺序和老 openCandidate 保持一致：低波动处理、环境缩放、手续费/RR、强平缓冲、仓位、回撤保护。</p>
     */
    SizingResult buildPlan(String symbol, User user, BigDecimal totalEquity,
                           QuantSignalDecision signal, MarketContext ctx,
                           EntryStrategyCandidate candidate, double riskScale,
                           SymbolProfile profile, TradingOperations tools,
                           TradingRuntimeToggles toggles) {
        SlTpAdjustment adj = adjustForNoiseFloor(candidate.slDistance(), candidate.tpDistance(), ctx.price, profile);
        if (adj == null) {
            return SizingResult.reject(String.format("%s: 极端低波动 ATR/price=%.3f%% 扩SL>%.1fx→放弃",
                    candidate.label(), ctx.atr5m.doubleValue() / ctx.price.doubleValue() * 100, LOW_VOL_SL_EXPAND_MAX));
        }
        boolean isLowVol = adj.positionScale() < 1.0;
        if (isLowVol && !toggles.lowVolTradingEnabled()) {
            return SizingResult.reject(candidate.label() + ": " + adj.note() + " → HOLD(低波动交易被管理端关闭)");
        }

        double regimeScale = pathRegimeScale(candidate.path(), ctx, signal);
        if (regimeScale <= 0) {
            return SizingResult.reject(candidate.label() + ": regime=" + ctx.regime + "且信号弱→观望");
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
        BigDecimal takeProfit = candidate.isLong() ? price.add(tpDistance) : price.subtract(tpDistance);

        String feeCheck = checkFeeAwareRR(price, ctx.atr5m, tpDistance);
        if (feeCheck != null) return SizingResult.reject(candidate.label() + ": " + feeCheck);
        String rrGuard = checkRiskRewardGuard(symbol, candidate.path(), slDistance, tpDistance);
        if (rrGuard != null) return SizingResult.reject(candidate.label() + ": " + rrGuard);

        int leverage = Math.min(signal.getMaxLeverage() != null ? signal.getMaxLeverage() : 10,
                candidate.maxLeverage());
        String liquidationCheck = checkLiquidationBuffer(candidate.isLong(), price, slDistance, leverage);
        if (liquidationCheck != null) return SizingResult.reject(candidate.label() + ": " + liquidationCheck);

        BigDecimal quantity = calcQuantityByRisk(user, totalEquity, price, slDistance, leverage,
                effectiveScale, signal.getMaxPositionPct());
        if (quantity == null) {
            return SizingResult.reject(candidate.label() + ": 仓位计算失败(余额不足或超限)");
        }

        BigDecimal drawdownReferenceEquity = drawdownReferenceEquity(totalEquity, tools);
        boolean inDrawdown = isInDrawdown(totalEquity, drawdownReferenceEquity);
        if (inDrawdown) {
            leverage = Math.max(5, (int) (leverage * DRAWDOWN_REDUCTION.doubleValue()));
            quantity = quantity.multiply(DRAWDOWN_REDUCTION).setScale(8, RoundingMode.HALF_DOWN);
        }

        return SizingResult.accept(new EntryOrderPlan(slDistance, tpDistance, stopLoss, takeProfit,
                leverage, quantity, effectiveScale, isLowVol ? adj.note() : null, inDrawdown));
    }

    /**
     * 按行情环境调整策略仓位。
     *
     * <p>SHOCK 只允许高置信度小仓位参与；SQUEEZE 对突破更友好，对其它路径保守缩放。</p>
     */
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

    /**
     * 手续费感知的止盈空间检查。
     *
     * <p>只看 TP 是否覆盖预估往返手续费后仍留下最低 ATR 利润空间。</p>
     */
    private String checkFeeAwareRR(BigDecimal price, BigDecimal atr, BigDecimal tpDistance) {
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

    /**
     * 最低入场盈亏比保护。
     *
     * <p>MR 是胜率型回归策略，允许使用更低的最低 R:R；其它路径使用默认门槛。</p>
     */
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

    /**
     * 低波动噪声底线处理。
     *
     * <p>策略 ATR 止损小于品种最低百分比止损时，同步放大 SL/TP 并缩小仓位。</p>
     */
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

    /**
     * 确认止损会早于强平触发。
     *
     * <p>如果 SL 过宽，价格可能先打到强平线，单笔风险预算会失效，必须拒绝开仓。</p>
     */
    private String checkLiquidationBuffer(boolean isLong, BigDecimal price, BigDecimal slDistance, int leverage) {
        if (price == null || price.signum() <= 0 || slDistance == null || slDistance.signum() <= 0) return null;
        double liqDistancePct = estimateLiquidationDistancePct(isLong, leverage);
        if (liqDistancePct <= 0) {
            return "杠杆过高，初始保证金率不高于维持保证金率";
        }

        double slPct = slDistance.doubleValue() / price.doubleValue();
        double maxAllowedSlPct = liqDistancePct * MAX_SL_TO_LIQ_DISTANCE_RATIO;
        if (slPct > maxAllowedSlPct) {
            return String.format("SL距当前价%.2f%% > 强平距离安全上限%.2f%%(liq≈%.2f%%, lev=%dx)",
                    slPct * 100, maxAllowedSlPct * 100, liqDistancePct * 100, leverage);
        }
        return null;
    }

    private double estimateLiquidationDistancePct(boolean isLong, int leverage) {
        if (leverage <= 0) return 0;
        double initialMarginRate = 1.0 / leverage;
        double numerator = initialMarginRate - MAINTENANCE_MARGIN_RATE;
        if (numerator <= 0) return 0;
        double denominator = isLong ? (1.0 - MAINTENANCE_MARGIN_RATE) : (1.0 + MAINTENANCE_MARGIN_RATE);
        return numerator / denominator;
    }

    /**
     * 按单笔风险预算计算下单数量。
     *
     * <p>有效止损距离 = 止损距离 + 预估平仓手续费距离，避免 SL 触发时真实亏损超过预算。</p>
     */
    private BigDecimal calcQuantityByRisk(User user, BigDecimal totalEquity,
                                          BigDecimal price,
                                          BigDecimal slDistance, int leverage,
                                          double scale,
                                          BigDecimal maxPositionPct) {
        BigDecimal balance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal frozen = user.getFrozenBalance() != null ? user.getFrozenBalance() : BigDecimal.ZERO;
        BigDecimal equity = totalEquity != null ? totalEquity : balance.add(frozen);
        BigDecimal effectiveSlDistance = slDistance.add(price.multiply(BigDecimal.valueOf(ESTIMATED_CLOSE_FEE_RATE)));
        BigDecimal quantity = equity.multiply(RISK_PER_TRADE)
                .multiply(BigDecimal.valueOf(scale))
                .divide(effectiveSlDistance, 8, RoundingMode.HALF_DOWN);
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

    /**
     * 获取回撤保护基准权益。
     *
     * <p>生产环境优先使用熔断服务维护的 peak equity；没有峰值时退回初始资金和当前权益中的较大值。</p>
     */
    private BigDecimal drawdownReferenceEquity(BigDecimal totalEquity, TradingOperations tools) {
        BigDecimal reference = DeterministicTradingExecutor.INITIAL_BALANCE;
        if (totalEquity != null && totalEquity.compareTo(reference) > 0) {
            reference = totalEquity;
        }
        try {
            BigDecimal peakEquity = tools != null ? tools.peakEquity() : null;
            if (peakEquity != null && peakEquity.compareTo(reference) > 0) {
                reference = peakEquity;
            }
        } catch (Exception e) {
            log.warn("[EntryDecision] peakEquity读取失败，使用本地基准 totalEquity={}", totalEquity, e);
        }
        return reference;
    }

    /**
     * 判断账户是否进入回撤保护。
     */
    private boolean isInDrawdown(BigDecimal totalEquity, BigDecimal referenceEquity) {
        if (totalEquity == null || referenceEquity == null || referenceEquity.signum() <= 0) return false;
        return totalEquity.compareTo(referenceEquity.multiply(DRAWDOWN_THRESHOLD)) < 0;
    }

    record EntryOrderPlan(BigDecimal slDistance, BigDecimal tpDistance,
                          BigDecimal stopLoss, BigDecimal takeProfit,
                          int leverage, BigDecimal quantity,
                          double effectiveScale, String lowVolNote,
                          boolean inDrawdown) {
    }

    record SizingResult(EntryOrderPlan plan, String rejectReason) {

        static SizingResult accept(EntryOrderPlan plan) {
            return new SizingResult(plan, null);
        }

        static SizingResult reject(String reason) {
            return new SizingResult(null, reason);
        }
    }

    private record SlTpAdjustment(BigDecimal slDistance, BigDecimal tpDistance,
                                  double positionScale, String note) {
    }
}
