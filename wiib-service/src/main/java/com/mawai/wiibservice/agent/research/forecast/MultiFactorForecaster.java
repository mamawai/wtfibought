package com.mawai.wiibservice.agent.research.forecast;

import java.util.Map;

/**
 * 6/12/24h 多因子方向预测器：趋势腿（多均线排列 via {@link IndicatorAdapter}）+ 资金费腿 + 恐惧贪婪腿，等权正交合成。
 * 反过拟合：三腿等权、不网格寻优权重；fundingScale / ε 取固定默认、不调优（spec §5.3）。
 */
public final class MultiFactorForecaster implements Forecaster {

    /** 资金费标准化尺度（/8h）：极端 ±0.001 → tanh 偏满；固定不调优。 */
    public static final double DEFAULT_FUNDING_SCALE = 0.0005;
    /** deadband：合成净信号 |s| 需超此阈值才出方向，否则 FLAT。 */
    public static final double DEFAULT_EPSILON = 0.1;

    private final double fundingScale;
    private final double epsilon;

    public MultiFactorForecaster(double fundingScale, double epsilon) {
        this.fundingScale = fundingScale;
        this.epsilon = epsilon;
    }

    public static MultiFactorForecaster defaults() {
        return new MultiFactorForecaster(DEFAULT_FUNDING_SCALE, DEFAULT_EPSILON);
    }

    @Override
    public Forecast forecast(ResearchFeatures features) {
        Map<String, Object> ind = IndicatorAdapter.indicators(features.barsUpToNow());
        Object align = ind.get("ma_alignment");
        if (align == null) return Forecast.flat();                              // <30 bar 暖机 / 指标缺失
        double trendLeg = ((Number) align).intValue();                         // -1/0/+1：多均线排列方向
        double fundingLeg = -Math.tanh(features.fundingRate() / fundingScale); // 极端正资金费(多头拥挤)→偏空
        double fgLeg = (50.0 - features.fearGreed()) / 50.0;                   // 极恐→偏多、极贪→偏空
        double s = (trendLeg + fundingLeg + fgLeg) / 3.0;                       // 三腿等权合成
        int dir = s > epsilon ? 1 : (s < -epsilon ? -1 : 0);                   // deadband 内→FLAT
        double conf = Math.min(1.0, Math.abs(s));
        return new Forecast(dir, conf);
    }

    @Override
    public String name() {
        return "multi_factor_trend_funding_fng";
    }
}
