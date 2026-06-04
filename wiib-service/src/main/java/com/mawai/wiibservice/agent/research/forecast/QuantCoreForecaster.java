package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.stats.VolatilityEstimator;

import java.util.List;

/**
 * 量化核心多输出预测器：组装三腿——vol=EWMA（VolatilityEstimator）、regime=RegimeClassifier（ADX/ATR）、
 * direction=注入的单输出 {@link Forecaster}（默认复用 MultiFactorForecaster，已知方向 edge 弱，作 LLM 增量的对照基线）。
 * 三腿独立无耦合：vol/regime 无状态，direction 的状态(fit)由本类转发。
 */
public final class QuantCoreForecaster implements MultiOutputForecaster {

    public static final double DEFAULT_VOL_LAMBDA = 0.94; // RiskMetrics 典型 lambda

    private final ForecastHorizon horizon;
    private final double volLambda;
    private final Forecaster directionForecaster;

    public QuantCoreForecaster(ForecastHorizon horizon, double volLambda, Forecaster directionForecaster) {
        this.horizon = horizon;
        this.volLambda = volLambda;
        this.directionForecaster = directionForecaster;
    }

    /** 默认：EWMA(0.94) + RegimeClassifier 默认阈值 + MultiFactorForecaster.defaults() 方向腿。 */
    public static QuantCoreForecaster defaults(ForecastHorizon horizon) {
        return new QuantCoreForecaster(horizon, DEFAULT_VOL_LAMBDA, MultiFactorForecaster.defaults());
    }

    @Override
    public MultiOutputForecaster fit(List<TrainingSample> trainSamples) {
        return new QuantCoreForecaster(horizon, volLambda, directionForecaster.fit(trainSamples)); // 仅方向腿有状态
    }

    @Override
    public MultiOutputForecast forecast(ResearchFeatures features) {
        List<KlineBar> bars = features.barsUpToNow();
        double sigma = VolatilityEstimator.ewmaVolatility(bars, volLambda);
        RegimeVerdict regime = RegimeClassifier.classify(IndicatorAdapter.indicators(bars));
        Forecast direction = directionForecaster.forecast(features);
        return new MultiOutputForecast(horizon, sigma, regime.regime(), regime.confidence(), direction);
    }

    @Override
    public String name() {
        return "quant_core[" + directionForecaster.name() + "]";
    }
}
