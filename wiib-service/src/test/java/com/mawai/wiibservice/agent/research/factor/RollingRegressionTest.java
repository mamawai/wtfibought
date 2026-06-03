package com.mawai.wiibservice.agent.research.factor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RollingRegressionTest {

    @Test
    void betaRecoversKnownSlope() {
        double[] btc = {1.0, 2.0, 3.0, 4.0};
        double[] alt = {2.0, 4.0, 6.0, 8.0};

        assertThat(RollingRegression.beta(alt, btc, 4))
                .isCloseTo(2.0, within(1e-12));
    }

    @Test
    void betaUsesRequestedHistoryWindowEndingBeforeEndExclusive() {
        double[] btc = {1.0, 2.0, 10.0, 11.0};
        double[] alt = {1.0, 2.0, 20.0, 22.0};

        assertThat(RollingRegression.beta(alt, btc, 2, 2))
                .isCloseTo(1.0, within(1e-12));
        assertThat(RollingRegression.beta(alt, btc, 4, 2))
                .isCloseTo(2.0, within(1e-12));
    }

    @Test
    void betaIsNeutralWhenBenchmarkHasNoVariation() {
        assertThat(RollingRegression.beta(
                new double[]{1.0, 2.0, 3.0},
                new double[]{5.0, 5.0, 5.0},
                3)).isZero();
    }

    @Test
    void residualAtUsesPriorBetaAndCurrentReturn() {
        double[] btc = {1.0, 2.0, 3.0, 4.0};
        double[] alt = {2.0, 4.0, 6.0, 9.0};

        // index=3 的 beta 只用前三个点，beta=2；残差=9 - 2*4 = 1。
        assertThat(RollingRegression.residualAt(alt, btc, 3, 3))
                .isCloseTo(1.0, within(1e-12));
    }

    @Test
    void residualAtDoesNotLookAtFutureRows() {
        double[] btc = {1.0, 2.0, 3.0, 4.0, 1000.0};
        double[] alt = {2.0, 4.0, 6.0, 9.0, -9999.0};
        double before = RollingRegression.residualAt(alt, btc, 3, 3);

        btc[4] = -1000.0;
        alt[4] = 9999.0;
        double after = RollingRegression.residualAt(alt, btc, 3, 3);

        assertThat(after).isCloseTo(before, within(1e-12));
    }

    @Test
    void latestResidualUsesLastIndex() {
        double[] btc = {1.0, 2.0, 3.0, 4.0};
        double[] alt = {2.0, 4.0, 6.0, 8.5};

        assertThat(RollingRegression.latestResidual(alt, btc, 3))
                .isCloseTo(0.5, within(1e-12));
    }
}
