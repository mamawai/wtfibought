package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.ForecastHorizon;

import java.util.List;

/**
 * 把 feature-bar 级别的 σ 缩放到目标预测 horizon。
 * 例如 5m 特征预测 6h 风险，按 sqrt(6h / 5m) 缩放；若无法推断特征周期则保持原值。
 */
public final class HorizonScaledVolForecaster implements VolForecaster {

    private final VolForecaster base;
    private final ForecastHorizon horizon;

    public HorizonScaledVolForecaster(VolForecaster base, ForecastHorizon horizon) {
        if (base == null) {
            throw new IllegalArgumentException("base vol forecaster 不能为空");
        }
        if (horizon == null) {
            throw new IllegalArgumentException("horizon 不能为空");
        }
        this.base = base;
        this.horizon = horizon;
    }

    @Override
    public VolForecaster fit(List<TrainingSample> trainSamples) {
        return new HorizonScaledVolForecaster(base.fit(trainSamples), horizon);
    }

    @Override
    public double forecastSigma(ResearchFeatures features) {
        return scale(base.forecastSigma(features), features, horizon);
    }

    @Override
    public String name() {
        return "horizon_scaled(" + horizon.hours() + "h," + base.name() + ")";
    }

    public static double scale(double sigma, ResearchFeatures features, ForecastHorizon horizon) {
        if (!Double.isFinite(sigma) || sigma <= 0.0 || features == null || horizon == null) {
            return sigma;
        }
        long barMillis = features.inferredBarMillis();
        if (barMillis <= 0L) {
            return sigma;
        }
        double periods = (double) horizon.millis() / barMillis;
        if (!Double.isFinite(periods) || periods <= 0.0) {
            return sigma;
        }
        return sigma * Math.sqrt(periods);
    }
}
