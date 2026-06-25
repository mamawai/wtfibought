package com.mawai.wiibquant.agent.research.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForecastAccuracyComparisonTest {

    @Test
    void positiveLossDiffMeansModelIsBetter() {
        double[] model = {1, 1, 1, 1, 1, 1};
        double[] baseline = {2, 2, 2, 2, 2, 2};

        ForecastAccuracyComparison c = ForecastAccuracyComparison.compareLosses(model, baseline, 1);

        assertThat(c.meanLossDiff()).isGreaterThan(0.0);
        assertThat(c.pValue()).isEqualTo(0.0);
        assertThat(c.modelBetterAt(0.05)).isTrue();
    }

    @Test
    void negativeLossDiffIsNotBetter() {
        double[] model = {2, 2, 2, 2};
        double[] baseline = {1, 1, 1, 1};

        ForecastAccuracyComparison c = ForecastAccuracyComparison.compareLosses(model, baseline, 1);

        assertThat(c.meanLossDiff()).isLessThan(0.0);
        assertThat(c.modelBetterAt(0.05)).isFalse();
    }

    @Test
    void mismatchedLengthsRejected() {
        assertThatThrownBy(() -> ForecastAccuracyComparison.compareLosses(new double[]{1}, new double[]{1, 2}, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
