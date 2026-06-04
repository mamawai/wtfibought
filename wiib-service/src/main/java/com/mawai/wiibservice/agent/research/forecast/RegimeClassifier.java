package com.mawai.wiibservice.agent.research.forecast;

import java.util.Map;

/**
 * regime 量化核心腿：从 {@link IndicatorAdapter} 指标（ADX/ATR）分类市场状态。
 * 移植老 RegimeAgent 的纯代码思路（ADX 方向 + ATR 波动），砍掉 live-only 的 IV 腿。
 * 判定（SHOCK 优先）：
 *  atr_spike_ratio ≥ shockSpike → SHOCK；adx ≥ trendAdx → 趋势(plus/minus 定向)；否则 RANGING。
 * 阈值开放（默认 adx 50 / spike 2.0），暖机(空指标)→ RANGING/0。
 */
public final class RegimeClassifier {

    private RegimeClassifier() {
    }

    /** ADX ≥ 此值判趋势；全量 q=1.75 诊断下，经典 25 会把半数样本误作趋势，50 更贴近路径趋势占比。 */
    public static final double DEFAULT_TREND_ADX = 50.0;
    /** atr_spike_ratio（当前 ATR÷30均值）≥ 此值判 SHOCK（异常剧烈波动，优先级最高）。 */
    public static final double DEFAULT_SHOCK_SPIKE = 2.0;
    private static final double ADX_CONF_FULL = 70.0; // adx 达此值 趋势置信记满

    public static RegimeVerdict classify(Map<String, Object> indicators) {
        return classify(indicators, DEFAULT_TREND_ADX, DEFAULT_SHOCK_SPIKE);
    }

    public static RegimeVerdict classify(Map<String, Object> indicators, double trendAdx, double shockSpike) {
        if (indicators == null || indicators.isEmpty()) {
            return new RegimeVerdict(MarketRegime.RANGING, 0.0); // 暖机/无指标 → 中性
        }
        double spike = toDouble(indicators.get("atr_spike_ratio"));
        if (spike >= shockSpike) {
            return new RegimeVerdict(MarketRegime.SHOCK, Math.min(1.0, spike / (2 * shockSpike)));
        }
        double adx = toDouble(indicators.get("adx"));
        if (adx >= trendAdx) {
            double plusDi = toDouble(indicators.get("plus_di"));
            double minusDi = toDouble(indicators.get("minus_di"));
            MarketRegime regime = plusDi >= minusDi ? MarketRegime.TRENDING_UP : MarketRegime.TRENDING_DOWN;
            return new RegimeVerdict(regime, Math.min(1.0, adx / ADX_CONF_FULL));
        }
        return new RegimeVerdict(MarketRegime.RANGING, Math.max(0.0, 1.0 - adx / trendAdx));
    }

    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
