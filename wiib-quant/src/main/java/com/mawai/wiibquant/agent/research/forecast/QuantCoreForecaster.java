package com.mawai.wiibquant.agent.research.forecast;

import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibcommon.market.KlineBar;

import java.util.List;

/**
 * 量化核心多输出预测器：组装三腿——vol=可插拔 VolForecaster、regime=可插拔 RegimeForecaster、
 * direction=注入的单输出 {@link Forecaster}（默认复用 MultiFactorForecaster，已知方向 edge 弱，作 LLM 增量的对照基线）。
 * 三腿独立无耦合：各腿状态(fit)由本类转发。
 */
public final class QuantCoreForecaster implements MultiOutputForecaster {

    public static final double DEFAULT_VOL_LAMBDA = 0.94; // RiskMetrics 典型 lambda

    private final ForecastHorizon horizon;
    private final double volLambda;
    private final VolForecaster volForecaster;
    private final RegimeForecaster regimeForecaster;
    private final Forecaster directionForecaster;

    public QuantCoreForecaster(ForecastHorizon horizon, double volLambda, Forecaster directionForecaster) {
        this(horizon, volLambda, defaultVolForecaster(horizon, volLambda),
                new AdxAtrRegimeForecaster(), directionForecaster);
    }

    public QuantCoreForecaster(ForecastHorizon horizon, double volLambda,
                               VolForecaster volForecaster, Forecaster directionForecaster) {
        this(horizon, volLambda, volForecaster, new AdxAtrRegimeForecaster(), directionForecaster);
    }

    public QuantCoreForecaster(ForecastHorizon horizon, double volLambda,
                               VolForecaster volForecaster,
                               RegimeForecaster regimeForecaster,
                               Forecaster directionForecaster) {
        this.horizon = horizon;
        this.volLambda = volLambda;
        this.volForecaster = volForecaster;
        this.regimeForecaster = regimeForecaster;
        this.directionForecaster = directionForecaster;
    }

    /** 默认：EWMA(0.94) + ADX/ATR 当前状态 + MultiFactorForecaster.defaults() 方向腿。 */
    public static QuantCoreForecaster defaults(ForecastHorizon horizon) {
        return defaults(horizon, MultiFactorForecaster.defaults());
    }

    public static QuantCoreForecaster defaults(ForecastHorizon horizon, Forecaster directionForecaster) {
        return new QuantCoreForecaster(horizon, DEFAULT_VOL_LAMBDA, directionForecaster);
    }

    /** EWMA + ADX/ATR + 全 5 因子方向腿；365d 验证中 direction 明显优于原 fixed3，保留显式工厂避免改默认。 */
    public static QuantCoreForecaster allFactorDirection(ForecastHorizon horizon) {
        return defaults(horizon, MultiFactorForecaster.allFactors());
    }

    /** GK 输入的 HAR-RV 腿：保留唯一 HAR 候选；close-return HAR 已删除，避免 floor 病理回流。 */
    public static QuantCoreForecaster harRvGk(ForecastHorizon horizon) {
        return harRvGk(horizon, 5 * 60_000L);
    }

    public static QuantCoreForecaster harRvGk(ForecastHorizon horizon, long decisionBarMillis) {
        return new QuantCoreForecaster(horizon, DEFAULT_VOL_LAMBDA,
                new HorizonScaledVolForecaster(HarRvVolForecaster.gkDefaults(DEFAULT_VOL_LAMBDA, decisionBarMillis), horizon),
                MultiFactorForecaster.defaults());
    }

    /** EWMA + 训练窗 trailing-shape regime 转移腿；用于检验 future-regime 是否有比当前 ADX 更强的可预测性。 */
    public static QuantCoreForecaster trailingShapeRegime(ForecastHorizon horizon) {
        return trailingShapeRegime(horizon, 5 * 60_000L);
    }

    public static QuantCoreForecaster trailingShapeRegime(ForecastHorizon horizon, long decisionBarMillis) {
        return trailingShapeRegime(horizon, decisionBarMillis, MultiFactorForecaster.defaults());
    }

    public static QuantCoreForecaster trailingShapeRegime(ForecastHorizon horizon, long decisionBarMillis,
                                                         Forecaster directionForecaster) {
        return new QuantCoreForecaster(horizon, DEFAULT_VOL_LAMBDA,
                defaultVolForecaster(horizon, DEFAULT_VOL_LAMBDA),
                TrailingShapeTransitionRegimeForecaster.defaults(decisionBarMillis),
                directionForecaster);
    }

    private static VolForecaster defaultVolForecaster(ForecastHorizon horizon, double lambda) {
        return new HorizonScaledVolForecaster(new EwmaVolForecaster(lambda), horizon);
    }

    @Override
    public MultiOutputForecaster fit(List<TrainingSample> trainSamples) {
        return new QuantCoreForecaster(horizon, volLambda,
                volForecaster.fit(trainSamples),
                regimeForecaster.fit(trainSamples),
                directionForecaster.fit(trainSamples));
    }

    @Override
    public MultiOutputForecast forecast(ResearchFeatures features) {
        List<KlineBar> bars = features.barsUpToNow();
        double sigma = volForecaster.forecastSigma(features);
        VolatilityRiskContext volContext = VolatilityRiskContext.from(horizon, sigma, bars);
        RegimeVerdict regime = regimeForecaster.forecastRegime(features);
        Forecast direction = directionForecaster.forecast(features);
        return new MultiOutputForecast(horizon, sigma, volContext, regime.regime(), regime.confidence(), direction);
    }

    @Override
    public String name() {
        return "quant_core[vol=" + volForecaster.name()
                + ",regime=" + regimeForecaster.name()
                + ",dir=" + directionForecaster.name() + "]";
    }
}
