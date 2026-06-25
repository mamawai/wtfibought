package com.mawai.wiibquant.agent.research.forecast;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.research.stats.VolatilityEstimator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VolForecasterTest {

    @Test
    void ewmaMatchesExistingEstimator() {
        List<KlineBar> bars = bars(60);
        EwmaVolForecaster fc = new EwmaVolForecaster(0.94);

        assertThat(fc.forecastSigma(ResearchFeatures.ofBars(bars)))
                .isEqualTo(VolatilityEstimator.ewmaVolatility(bars, 0.94));
    }

    @Test
    void harRvFallsBackWhenHistoryIsTooShort() {
        VolForecaster fallback = new ConstantVolForecaster(0.123);
        HarRvVolForecaster fc = new HarRvVolForecaster(1, 3, 5, 8, fallback);

        assertThat(fc.forecastSigma(ResearchFeatures.ofBars(bars(8))))
                .isCloseTo(0.123, within(1e-12));
    }

    @Test
    void harRvReturnsFinitePositiveSigmaWithEnoughHistory() {
        HarRvVolForecaster fc = new HarRvVolForecaster(1, 3, 5, 8, new ConstantVolForecaster(0.123));

        double sigma = fc.forecastSigma(ResearchFeatures.ofBars(bars(80)));

        assertThat(sigma).isGreaterThan(0.0);
        assertThat(Double.isFinite(sigma)).isTrue();
    }

    @Test
    void harRvUsesHistoryAtMinimumObservationBoundary() {
        HarRvVolForecaster fc = new HarRvVolForecaster(1, 3, 5, 8, new ConstantVolForecaster(-1.0));

        double sigma = fc.forecastSigma(ResearchFeatures.ofBars(bars(14)));

        assertThat(sigma).isGreaterThan(0.0);
        assertThat(Double.isFinite(sigma)).isTrue();
    }

    private static List<KlineBar> bars(int n) {
        List<KlineBar> out = new ArrayList<>(n);
        double price = 100.0;
        for (int i = 0; i < n; i++) {
            double open = price;
            double drift = 0.002 * Math.sin(i / 3.0);
            double vol = i % 12 < 6 ? 0.004 : 0.012;
            price *= Math.exp(drift + vol * Math.sin(i * 1.7));
            double close = price;
            BigDecimal o = BigDecimal.valueOf(open);
            BigDecimal c = BigDecimal.valueOf(close);
            BigDecimal high = BigDecimal.valueOf(Math.max(open, close) * 1.001);
            BigDecimal low = BigDecimal.valueOf(Math.min(open, close) * 0.999);
            out.add(new KlineBar(i, i, o, high, low, c, BigDecimal.ONE));
        }
        return out;
    }

    private record ConstantVolForecaster(double sigma) implements VolForecaster {
        @Override
        public double forecastSigma(ResearchFeatures features) {
            return sigma;
        }

        @Override
        public String name() {
            return "constant_vol";
        }
    }
}
