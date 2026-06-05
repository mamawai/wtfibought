package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.stats.VolatilityEstimator;
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

    @Test
    void bucketCalibrationLearnsScaleFromTrainingForwardReturns() {
        BucketCalibratedVolForecaster fc = new BucketCalibratedVolForecaster(new FundingVolForecaster(), 2, 2);

        VolForecaster trained = fc.fit(List.of(
                sample(0.010, 0.020),
                sample(0.011, 0.022),
                sample(0.040, 0.020),
                sample(0.044, 0.022)));

        assertThat(trained.forecastSigma(features(0.0105))).isCloseTo(0.021, within(1e-12));
        assertThat(trained.forecastSigma(features(0.0500))).isCloseTo(0.025, within(1e-12));
    }

    @Test
    void bucketCalibrationFallsBackWhenForwardReturnsAreMissing() {
        BucketCalibratedVolForecaster fc = new BucketCalibratedVolForecaster(new FundingVolForecaster(), 2, 2);

        VolForecaster trained = fc.fit(List.of(
                new TrainingSample(features(0.010), BigDecimal.ZERO),
                new TrainingSample(features(0.020), BigDecimal.ZERO),
                new TrainingSample(features(0.030), BigDecimal.ZERO),
                new TrainingSample(features(0.040), BigDecimal.ZERO)));

        assertThat(trained.forecastSigma(features(0.030))).isCloseTo(0.030, within(1e-12));
    }

    @Test
    void bucketCalibrationFallsBackWhenTrainingBucketIsTooSmall() {
        BucketCalibratedVolForecaster fc = new BucketCalibratedVolForecaster(new FundingVolForecaster(), 5, 3);

        VolForecaster trained = fc.fit(List.of(
                sample(0.010, 0.020),
                sample(0.040, 0.020)));

        assertThat(trained.forecastSigma(features(0.010))).isCloseTo(0.010, within(1e-12));
    }

    @Test
    void varianceShrinkageFallsBackWhenTrainingWindowIsTooSmall() {
        VarianceShrinkageVolForecaster fc = new VarianceShrinkageVolForecaster(new FundingVolForecaster(), 3);

        VolForecaster trained = fc.fit(List.of(
                sample(0.010, 0.020),
                sample(0.080, 0.020)));

        assertThat(((VarianceShrinkageVolForecaster) trained).learnedWeight()).isEqualTo(1.0);
        assertThat(trained.forecastSigma(features(0.120))).isCloseTo(0.120, within(1e-12));
    }

    @Test
    void varianceShrinkageLearnsClimatologyWhenRawSigmaIsNoisy() {
        VarianceShrinkageVolForecaster fc = new VarianceShrinkageVolForecaster(new FundingVolForecaster(), 3);

        VolForecaster trained = fc.fit(List.of(
                sample(0.010, 0.040),
                sample(0.080, -0.040),
                sample(0.020, 0.040),
                sample(0.100, -0.040)));

        assertThat(((VarianceShrinkageVolForecaster) trained).learnedWeight()).isEqualTo(0.0);
        assertThat(trained.forecastSigma(features(0.200))).isCloseTo(0.040, within(1e-12));
    }

    @Test
    void varianceShrinkageKeepsRawWhenRawSigmaMatchesForwardReturns() {
        VarianceShrinkageVolForecaster fc = new VarianceShrinkageVolForecaster(new FundingVolForecaster(), 3);

        VolForecaster trained = fc.fit(List.of(
                sample(0.010, 0.010),
                sample(0.020, -0.020),
                sample(0.040, 0.040),
                sample(0.080, -0.080)));

        assertThat(((VarianceShrinkageVolForecaster) trained).learnedWeight()).isEqualTo(1.0);
        assertThat(trained.forecastSigma(features(0.050))).isCloseTo(0.050, within(1e-12));
    }

    @Test
    void climatologyLearnsTrainingForwardReturnRms() {
        ClimatologyVolForecaster fc = new ClimatologyVolForecaster(
                com.mawai.wiibservice.agent.research.ForecastHorizon.H6, new FundingVolForecaster(), 3);

        VolForecaster trained = fc.fit(List.of(
                sample(0.010, 0.030),
                sample(0.020, -0.030),
                sample(0.030, 0.060)));

        double expected = Math.sqrt((0.030 * 0.030 + 0.030 * 0.030 + 0.060 * 0.060) / 3.0);
        assertThat(trained.forecastSigma(features(0.500))).isCloseTo(expected, within(1e-12));
    }

    @Test
    void climatologyFallsBackWhenTrainingWindowIsTooSmall() {
        ClimatologyVolForecaster fc = new ClimatologyVolForecaster(
                com.mawai.wiibservice.agent.research.ForecastHorizon.H6, new FundingVolForecaster(), 3);

        VolForecaster trained = fc.fit(List.of(
                sample(0.010, 0.030),
                sample(0.020, -0.030)));

        assertThat(trained.forecastSigma(features(0.120))).isCloseTo(0.120, within(1e-12));
    }

    @Test
    void climatologyUpdatesWithNewlyKnownHorizonReturns() {
        ClimatologyVolForecaster fc = new ClimatologyVolForecaster(
                com.mawai.wiibservice.agent.research.ForecastHorizon.H6, new FundingVolForecaster(), 1);
        VolForecaster trained = fc.fit(List.of(new TrainingSample(
                ResearchFeatures.ofBars(List.of(hourBar(0, 100.0))), BigDecimal.ZERO, 0.030)));

        double sigma = trained.forecastSigma(ResearchFeatures.ofBars(List.of(
                hourBar(0, 100.0),
                hourBar(1, 100.0),
                hourBar(2, 100.0),
                hourBar(3, 100.0),
                hourBar(4, 100.0),
                hourBar(5, 100.0),
                hourBar(6, 100.0),
                hourBar(7, 100.0 * Math.exp(0.090)))));

        double expected = Math.sqrt((0.030 * 0.030 + 0.090 * 0.090) / 2.0);
        assertThat(sigma).isCloseTo(expected, within(1e-12));
    }

    private static List<KlineBar> bars(int n) {
        List<KlineBar> out = new ArrayList<>(n);
        double price = 100.0;
        for (int i = 0; i < n; i++) {
            double drift = 0.002 * Math.sin(i / 3.0);
            double vol = i % 12 < 6 ? 0.004 : 0.012;
            price *= Math.exp(drift + vol * Math.sin(i * 1.7));
            BigDecimal close = BigDecimal.valueOf(price);
            out.add(new KlineBar(i, i, close, close, close, close, BigDecimal.ONE));
        }
        return out;
    }

    private static TrainingSample sample(double rawSigma, double realizedForwardReturn) {
        return new TrainingSample(features(rawSigma), BigDecimal.ZERO, realizedForwardReturn);
    }

    private static ResearchFeatures features(double rawSigma) {
        return new ResearchFeatures(List.of(), rawSigma, 50, 0.0, 0.0);
    }

    private static KlineBar hourBar(int hour, double close) {
        long t = hour * 3_600_000L;
        BigDecimal c = BigDecimal.valueOf(close);
        return new KlineBar(t, t, c, c, c, c, BigDecimal.ONE);
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

    private record FundingVolForecaster() implements VolForecaster {
        @Override
        public double forecastSigma(ResearchFeatures features) {
            return features.fundingRate();
        }

        @Override
        public String name() {
            return "funding_vol";
        }
    }
}
