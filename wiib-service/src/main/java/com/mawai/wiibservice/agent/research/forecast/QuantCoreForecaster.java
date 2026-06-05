package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.List;

/**
 * 量化核心多输出预测器：组装三腿——vol=可插拔 VolForecaster、regime=可插拔 RegimeForecaster、
 * direction=注入的单输出 {@link Forecaster}（默认复用 MultiFactorForecaster，已知方向 edge 弱，作 LLM 增量的对照基线）。
 * 三腿独立无耦合：各腿状态(fit)由本类转发。
 */
public final class QuantCoreForecaster implements MultiOutputForecaster {

    public static final double DEFAULT_VOL_LAMBDA = 0.94; // RiskMetrics 典型 lambda
    public static final int DEFAULT_VOL_SHRINKAGE_MIN_OBSERVATIONS = 500;

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

    /** HAR-RV 波动率腿 + 默认方向/regime 腿；用于研究评估中和 EWMA vol baseline 做同口径对照。 */
    public static QuantCoreForecaster harRv(ForecastHorizon horizon) {
        return harRv(horizon, 5 * 60_000L);
    }

    public static QuantCoreForecaster harRv(ForecastHorizon horizon, long decisionBarMillis) {
        return new QuantCoreForecaster(horizon, DEFAULT_VOL_LAMBDA,
                new HorizonScaledVolForecaster(HarRvVolForecaster.defaults(DEFAULT_VOL_LAMBDA, decisionBarMillis), horizon),
                MultiFactorForecaster.defaults());
    }

    /** EWMA + 训练窗分桶校准；仅用于 research 样本外实验，默认口径仍保留 raw EWMA。 */
    public static QuantCoreForecaster bucketCalibratedEwma(ForecastHorizon horizon) {
        return new QuantCoreForecaster(horizon, DEFAULT_VOL_LAMBDA,
                new BucketCalibratedVolForecaster(defaultVolForecaster(horizon, DEFAULT_VOL_LAMBDA), 5, 20),
                MultiFactorForecaster.defaults());
    }

    /** EWMA horizon variance + 训练窗常数方差收缩；只作为 research 候选，不替换默认 raw EWMA。 */
    public static QuantCoreForecaster varianceShrinkageEwma(ForecastHorizon horizon) {
        return varianceShrinkageEwma(horizon, MultiFactorForecaster.defaults());
    }

    public static QuantCoreForecaster varianceShrinkageEwma(ForecastHorizon horizon,
                                                            Forecaster directionForecaster) {
        return varianceShrinkageEwma(horizon, DEFAULT_VOL_SHRINKAGE_MIN_OBSERVATIONS, directionForecaster);
    }

    public static QuantCoreForecaster varianceShrinkageEwma(ForecastHorizon horizon, int minObservations) {
        return varianceShrinkageEwma(horizon, minObservations, MultiFactorForecaster.defaults());
    }

    public static QuantCoreForecaster varianceShrinkageEwma(ForecastHorizon horizon, int minObservations,
                                                            Forecaster directionForecaster) {
        return new QuantCoreForecaster(horizon, DEFAULT_VOL_LAMBDA,
                varianceShrinkageVolForecaster(horizon, minObservations),
                directionForecaster);
    }

    /** 训练窗 horizon return RMS 常数波动率腿；用于检验 constant baseline 是否就是更稳的 vol 口径。 */
    public static QuantCoreForecaster climatologyVol(ForecastHorizon horizon) {
        return climatologyVol(horizon, MultiFactorForecaster.defaults());
    }

    public static QuantCoreForecaster climatologyVol(ForecastHorizon horizon, Forecaster directionForecaster) {
        return new QuantCoreForecaster(horizon, DEFAULT_VOL_LAMBDA,
                climatologyVolForecaster(horizon, DEFAULT_VOL_SHRINKAGE_MIN_OBSERVATIONS),
                directionForecaster);
    }

    public static QuantCoreForecaster climatologyTrailingShapeRegime(ForecastHorizon horizon,
                                                                     long decisionBarMillis) {
        return climatologyTrailingShapeRegime(horizon, decisionBarMillis, MultiFactorForecaster.defaults());
    }

    public static QuantCoreForecaster climatologyTrailingShapeRegime(ForecastHorizon horizon,
                                                                     long decisionBarMillis,
                                                                     Forecaster directionForecaster) {
        return new QuantCoreForecaster(horizon, DEFAULT_VOL_LAMBDA,
                climatologyVolForecaster(horizon, DEFAULT_VOL_SHRINKAGE_MIN_OBSERVATIONS),
                TrailingShapeTransitionRegimeForecaster.defaults(decisionBarMillis),
                directionForecaster);
    }

    /** shrinkage vol + trailing-shape regime：用于 extended runner 中保持 regime 候选口径对称。 */
    public static QuantCoreForecaster varianceShrinkageTrailingShapeRegime(ForecastHorizon horizon,
                                                                           long decisionBarMillis) {
        return varianceShrinkageTrailingShapeRegime(horizon, decisionBarMillis, MultiFactorForecaster.defaults());
    }

    public static QuantCoreForecaster varianceShrinkageTrailingShapeRegime(ForecastHorizon horizon,
                                                                           long decisionBarMillis,
                                                                           Forecaster directionForecaster) {
        return new QuantCoreForecaster(horizon, DEFAULT_VOL_LAMBDA,
                varianceShrinkageVolForecaster(horizon, DEFAULT_VOL_SHRINKAGE_MIN_OBSERVATIONS),
                TrailingShapeTransitionRegimeForecaster.defaults(decisionBarMillis),
                directionForecaster);
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

    private static VolForecaster varianceShrinkageVolForecaster(ForecastHorizon horizon, int minObservations) {
        return new VarianceShrinkageVolForecaster(
                defaultVolForecaster(horizon, DEFAULT_VOL_LAMBDA), minObservations);
    }

    private static VolForecaster climatologyVolForecaster(ForecastHorizon horizon, int minObservations) {
        return new ClimatologyVolForecaster(
                horizon, defaultVolForecaster(horizon, DEFAULT_VOL_LAMBDA), minObservations);
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
