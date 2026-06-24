package com.mawai.wiibservice.agent.research.forecast;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 6/12/24h 多因子方向预测器：可开关的因子腿集合，等权正交合成。
 * 腿：趋势(多均线排列 via {@link IndicatorAdapter}) / 资金费 / 恐惧贪婪 / ETF 净流入 / 稳定币供给差。
 * measure-first：用 {@link #defaults()}(原 3 腿) vs {@link #onChainOnly()}(纯链上) vs {@link #allFactors()}(全 5 腿)
 * 同框对比，直接测出链上因子的独立 edge 与边际增益。
 * 反过拟合：腿等权、不网格寻优权重；fundingScale / ε 取固定默认；链上腿用方向符号、不引待调尺度（spec §5.3）。
 */
public final class MultiFactorForecaster implements Forecaster {

    /** 可参与合成的因子腿。 */
    public enum Leg {TREND, FUNDING, FNG, ETF, STABLECOIN}

    /** 资金费标准化尺度（/8h）：极端 ±0.001 → tanh 偏满；固定不调优。 */
    public static final double DEFAULT_FUNDING_SCALE = 0.0005;
    /** deadband：合成净信号 |s| 需超此阈值才出方向，否则 FLAT。 */
    public static final double DEFAULT_EPSILON = 0.1;

    private final Set<Leg> legs;
    private final double fundingScale;
    private final double epsilon;
    private final String name;

    public MultiFactorForecaster(Set<Leg> legs, double fundingScale, double epsilon, String name) {
        this.legs = EnumSet.copyOf(legs);
        this.fundingScale = fundingScale;
        this.epsilon = epsilon;
        this.name = name;
    }

    /** 原 3 腿（趋势+资金费+恐惧贪婪），行为与 Slice2 完全一致——作为多因子基准。 */
    public static MultiFactorForecaster defaults() {
        return new MultiFactorForecaster(EnumSet.of(Leg.TREND, Leg.FUNDING, Leg.FNG),
                DEFAULT_FUNDING_SCALE, DEFAULT_EPSILON, "multi_factor_trend_funding_fng");
    }

    /** 纯链上两腿（ETF 净流入 + 稳定币供给差）：测链上因子的独立 edge。 */
    public static MultiFactorForecaster onChainOnly() {
        return new MultiFactorForecaster(EnumSet.of(Leg.ETF, Leg.STABLECOIN),
                DEFAULT_FUNDING_SCALE, DEFAULT_EPSILON, "onchain_etf_stablecoin");
    }

    /** 全 5 腿：测链上叠加到原 3 腿之上的边际增益。 */
    public static MultiFactorForecaster allFactors() {
        return new MultiFactorForecaster(EnumSet.allOf(Leg.class),
                DEFAULT_FUNDING_SCALE, DEFAULT_EPSILON, "multi_factor_all5");
    }

    @Override
    public Forecast forecast(ResearchFeatures features) {
        double sum = 0;
        int n = 0;
        for (Leg leg : legs) {
            Double v = legSignal(leg, features);
            if (v == null) return Forecast.flat();   // 任一启用腿缺数据(如趋势腿<30bar 暖机)→ 整体 FLAT（保守，且与原 3 腿暖机口径一致）
            sum += v;
            n++;
        }
        if (n == 0) return Forecast.flat();
        double s = sum / n;                           // 启用腿等权
        int dir = s > epsilon ? 1 : (s < -epsilon ? -1 : 0);   // deadband 内→FLAT
        return new Forecast(dir, Math.min(1.0, Math.abs(s)));
    }

    /** 各腿信号 ∈ [-1,1]；趋势腿暖机不足返回 null（触发整体 FLAT）。 */
    private Double legSignal(Leg leg, ResearchFeatures f) {
        return switch (leg) {
            case TREND -> {
                Map<String, Object> ind = IndicatorAdapter.researchIndicators(f.barsUpToNow());
                Object align = ind.get("ma_alignment");
                yield align == null ? null : (double) ((Number) align).intValue();   // -1/0/+1 多均线排列
            }
            case FUNDING -> -Math.tanh(f.fundingRate() / fundingScale);   // 极端正资金费(多头拥挤)→偏空
            case FNG -> (50.0 - f.fearGreed()) / 50.0;                    // 极恐→偏多、极贪→偏空
            case ETF -> Math.signum(f.etfFlow());                        // 净流入→偏多（首测用方向符号）
            case STABLECOIN -> Math.signum(f.stablecoinDelta());         // 供给增(铸币入场)→偏多
        };
    }

    @Override
    public String name() {
        return name;
    }
}
