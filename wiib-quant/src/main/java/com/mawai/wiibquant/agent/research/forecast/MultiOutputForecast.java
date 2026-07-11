package com.mawai.wiibquant.agent.research.forecast;

import com.mawai.wiibquant.agent.research.ForecastHorizon;

/**
 * 多输出预测契约：新 agent 一次产三件一等输出 + 派生定仓提示。
 * - {@code expectedVolatility}：该 horizon 周期的期望实现波动（≥0，与 VolatilityEstimator 同口径）。
 * - {@code volatilityContext}：把 expectedVolatility 转成 bps、历史分位、风险层级和 LLM compact context。
 * - {@code regime} + {@code regimeConfidence∈[0,1]}：市场状态及其置信。
 * - {@code direction}：方向腿，复用 {@link Forecast}（±1/0 + confidence∈[0,1]），
 *   可经 {@link MultiOutputForecaster#asDirectionForecaster()} 直接跑进 ResearchEvalService 方向尺子。
 * 三输出各被尺子独立度量：方向凭样本外跑赢基准赢得 trading 权重，vol/regime 无论如何贡献风控价值。
 */
public record MultiOutputForecast(
        ForecastHorizon horizon,
        double expectedVolatility,
        VolatilityRiskContext volatilityContext,
        MarketRegime regime,
        double regimeConfidence,
        Forecast direction) {

    public MultiOutputForecast(ForecastHorizon horizon, double expectedVolatility,
                               MarketRegime regime, double regimeConfidence, Forecast direction) {
        this(horizon, expectedVolatility, defaultVolatilityContext(horizon, expectedVolatility),
                regime, regimeConfidence, direction);
    }

    public MultiOutputForecast {
        if (horizon == null) throw new IllegalArgumentException("horizon 不能为空");
        if (regime == null) throw new IllegalArgumentException("regime 不能为空");
        if (direction == null) throw new IllegalArgumentException("direction 不能为空");
        if (!Double.isFinite(expectedVolatility) || expectedVolatility < 0.0) {
            throw new IllegalArgumentException("expectedVolatility 必须为非负有限数: " + expectedVolatility);
        }
        if (volatilityContext == null) {
            volatilityContext = VolatilityRiskContext.from(horizon, expectedVolatility, java.util.List.of());
        }
        if (volatilityContext.horizon() != horizon) {
            throw new IllegalArgumentException("volatilityContext horizon 不一致: " + volatilityContext.horizon() + " vs " + horizon);
        }
        if (Math.abs(volatilityContext.expectedVolatility() - expectedVolatility) > 1e-12) {
            throw new IllegalArgumentException("volatilityContext expectedVolatility 不一致: "
                    + volatilityContext.expectedVolatility() + " vs " + expectedVolatility);
        }
        if (regimeConfidence < 0 || regimeConfidence > 1) {
            throw new IllegalArgumentException("regimeConfidence 须落在 [0,1]: " + regimeConfidence);
        }
    }

    /** 中性预测：无波动估计、震荡 regime、零置信、方向空仓。缺数据/暖机用。 */
    public static MultiOutputForecast neutral(ForecastHorizon horizon) {
        return new MultiOutputForecast(horizon, 0.0, MarketRegime.RANGING, 0.0, Forecast.flat());
    }

    private static VolatilityRiskContext defaultVolatilityContext(ForecastHorizon horizon, double expectedVolatility) {
        return horizon == null ? null : VolatilityRiskContext.from(horizon, expectedVolatility, java.util.List.of());
    }
}
