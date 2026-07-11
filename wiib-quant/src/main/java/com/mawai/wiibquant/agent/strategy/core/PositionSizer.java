package com.mawai.wiibquant.agent.strategy.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 风险定量单一真相源：回测引擎与实盘执行共用同一公式。
 * 曾是两份手抄实现靠注释声明"同公式"——调参只改一边会直接打破"回测判决对实盘有效"的根基。
 *
 * <p>qty = 权益 × 每笔风险% / 止损距离；保证金钳权益 15%；杠杆钳策略风控上限。</p>
 */
public final class PositionSizer {

    /** 单仓保证金上限占权益比例。 */
    public static final BigDecimal MAX_MARGIN_PCT = new BigDecimal("0.15");

    private PositionSizer() {
    }

    /** 实际杠杆 = min(请求杠杆, 策略风控上限)，下限 1。 */
    public static int effectiveLeverage(int requestedLeverage, StrategyRiskPolicy policy) {
        StrategyRiskPolicy p = policy == null ? StrategyRiskPolicy.defaults() : policy;
        return Math.max(1, Math.min(requestedLeverage, Math.max(1, p.maxLeverage())));
    }

    /** 风险定量下单数量；entry/sl/equity 任一无效返回 0，调用方按"不开仓"处理。 */
    public static BigDecimal riskSizedQty(BigDecimal equity, BigDecimal entry, BigDecimal stopLoss,
                                          StrategyRiskPolicy policy, int effectiveLeverage) {
        if (entry == null || entry.signum() <= 0 || stopLoss == null || equity == null || equity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal riskDistance = entry.subtract(stopLoss).abs();
        if (riskDistance.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        StrategyRiskPolicy p = policy == null ? StrategyRiskPolicy.defaults() : policy;
        BigDecimal riskPct = BigDecimal.valueOf(p.riskPerTradePct() > 0
                ? p.riskPerTradePct()
                : StrategyRiskPolicy.defaults().riskPerTradePct());
        BigDecimal qty = equity.multiply(riskPct).divide(riskDistance, 8, RoundingMode.DOWN);
        BigDecimal margin = qty.multiply(entry).divide(BigDecimal.valueOf(effectiveLeverage), 8, RoundingMode.CEILING);
        BigDecimal maxMargin = equity.multiply(MAX_MARGIN_PCT);
        if (margin.compareTo(maxMargin) > 0) {
            qty = maxMargin.multiply(BigDecimal.valueOf(effectiveLeverage)).divide(entry, 8, RoundingMode.DOWN);
        }
        return qty;
    }
}
