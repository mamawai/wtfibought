package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.stats.VolatilityEstimator;

import java.util.List;

/** EWMA 波动率预测腿；保持 QuantCore 原默认口径。 */
public final class EwmaVolForecaster implements VolForecaster {

    private final double lambda;

    public EwmaVolForecaster(double lambda) {
        this.lambda = lambda;
    }

    @Override
    public double forecastSigma(ResearchFeatures features) {
        List<KlineBar> bars = features == null ? List.of() : features.barsUpToNow();
        return VolatilityEstimator.ewmaVolatility(bars, lambda);
    }

    @Override
    public String name() {
        return "ewma_vol(lambda=" + lambda + ")";
    }
}
