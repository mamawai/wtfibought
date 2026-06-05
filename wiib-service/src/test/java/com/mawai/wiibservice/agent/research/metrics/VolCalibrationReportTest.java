package com.mawai.wiibservice.agent.research.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class VolCalibrationReportTest {

    @Test
    void overallRatioComparesRealizedVarianceToPredictedVariance() {
        double[] pred = {0.01, 0.02};
        double[] realized = {0.02, 0.04};

        VolCalibrationReport r = VolCalibrationReport.evaluate(pred, realized, 2);

        assertThat(r.n()).isEqualTo(2);
        assertThat(r.realizedToPredictedRatio()).isCloseTo(4.0, within(1e-9));
        assertThat(r.bucketCount()).isEqualTo(2);
        assertThat(r.buckets()).hasSize(2);
    }

    @Test
    void bucketsAreSortedByPredictedSigmaAndEqualFrequency() {
        double[] pred = {0.04, 0.01, 0.03, 0.02};
        double[] realized = {0.04, 0.01, 0.03, 0.02};

        VolCalibrationReport r = VolCalibrationReport.evaluate(pred, realized, 2);

        assertThat(r.buckets().get(0).n()).isEqualTo(2);
        assertThat(r.buckets().get(0).minPredictedSigma()).isCloseTo(0.01, within(1e-12));
        assertThat(r.buckets().get(0).maxPredictedSigma()).isCloseTo(0.02, within(1e-12));
        assertThat(r.buckets().get(1).minPredictedSigma()).isCloseTo(0.03, within(1e-12));
        assertThat(r.buckets().get(1).maxPredictedSigma()).isCloseTo(0.04, within(1e-12));
    }

    @Test
    void mismatchedLengthsRejected() {
        assertThatThrownBy(() -> VolCalibrationReport.evaluate(new double[]{0.01}, new double[]{0.01, 0.02}, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
