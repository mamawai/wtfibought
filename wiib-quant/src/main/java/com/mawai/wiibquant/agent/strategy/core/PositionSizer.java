package com.mawai.wiibquant.agent.strategy.core;

import com.mawai.wiibcommon.market.TradeFilterDefaults;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 风险定量单一真相源：回测引擎与实盘执行共用同一公式。
 * 曾是两份手抄实现靠注释声明"同公式"——调参只改一边会直接打破"回测判决对实盘有效"的根基。
 *
 * <p>qty = 权益 × 每笔风险% / 止损距离；保证金钳权益 15%；杠杆钳策略风控上限；
 * 出口按交易过滤器落地（步长向下对齐，低于最小数量/名义额=不开仓），sim 端同规则硬校验。</p>
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

    /** 风险定量下单数量（已按 filter 落地对齐）；entry/sl/equity 任一无效返回 0，调用方按"不开仓"处理。 */
    public static BigDecimal riskSizedQty(BigDecimal equity, BigDecimal entry, BigDecimal stopLoss,
                                          StrategyRiskPolicy policy, int effectiveLeverage,
                                          TradeFilterDefaults.Filter filter) {
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
        return applyFilter(qty, entry, filter);
    }

    /** 交易过滤器落地：步长向下对齐；对齐后低于最小数量或最小名义额返回 0（合规下不出的单就不下） */
    public static BigDecimal applyFilter(BigDecimal qty, BigDecimal price, TradeFilterDefaults.Filter filter) {
        if (filter == null || qty == null || qty.signum() <= 0) return qty;
        BigDecimal floored = TradeFilterDefaults.floorToStep(qty, filter.stepSize());
        if (floored.compareTo(filter.minQty()) < 0) return BigDecimal.ZERO;
        if (price != null && price.multiply(floored).compareTo(filter.minNotional()) < 0) return BigDecimal.ZERO;
        return floored;
    }
}
