package com.mawai.wiibquant.agent.research.factor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ContinuousFactorSignalsTest {

    @Test
    void riskAdjustedMomentumUsesCumulativeReturnOverReturnVolatility() {
        double[] closes = {100.0, 110.0, 105.0, 130.0};
        double[] returns = {
                Math.log(110.0 / 100.0),
                Math.log(105.0 / 110.0),
                Math.log(130.0 / 105.0)
        };
        double expected = Math.log(130.0 / 100.0) / FactorMath.volatility(returns);

        assertThat(ContinuousFactorSignals.riskAdjustedMomentum(closes, 4, 3))
                .isCloseTo(expected, within(1e-12));
    }

    @Test
    void shortReversalIsNegativeRiskAdjustedMomentum() {
        double[] closes = {100.0, 110.0, 105.0, 130.0};

        assertThat(ContinuousFactorSignals.shortReversal(closes, 4, 3))
                .isCloseTo(-ContinuousFactorSignals.riskAdjustedMomentum(closes, 4, 3), within(1e-12));
    }

    @Test
    void fundingCarryInvertsFundingZScore() {
        double[] funding = {0.0, 0.001, 0.002};
        double expected = -FactorMath.zScore(0.002, funding);

        assertThat(ContinuousFactorSignals.fundingCarry(funding, 3, 3))
                .isCloseTo(expected, within(1e-12));
    }

    @Test
    void volumeZScoreUsesLatestVolumeAgainstWindow() {
        double[] volumes = {100.0, 200.0, 400.0};

        assertThat(ContinuousFactorSignals.volumeZScore(volumes, 3, 3))
                .isCloseTo(FactorMath.zScore(400.0, volumes), within(1e-12));
    }

    @Test
    void amihudIlliquidityAveragesAbsoluteReturnOverDollarVolume() {
        double[] closes = {100.0, 110.0, 99.0};
        double[] volumes = {10.0, 20.0, 30.0};
        double expected = (
                Math.abs(Math.log(110.0 / 100.0)) / (110.0 * 20.0)
                        + Math.abs(Math.log(99.0 / 110.0)) / (99.0 * 30.0)
        ) / 2.0;

        assertThat(ContinuousFactorSignals.amihudIlliquidity(closes, volumes, 3, 2))
                .isCloseTo(expected, within(1e-12));
    }

    @Test
    void residualMomentumSumsResidualsWithPriorBeta() {
        double[] btc = {1.0, 2.0, 3.0, 4.0};
        double[] alt = {2.0, 4.0, 6.0, 9.0};

        assertThat(ContinuousFactorSignals.residualMomentum(alt, btc, 4, 1, 3))
                .isCloseTo(1.0, within(1e-12));
    }

    @Test
    void signalsDoNotReadFutureRows() {
        double[] closes = {100.0, 110.0, 105.0, 130.0, 9999.0};
        double before = ContinuousFactorSignals.riskAdjustedMomentum(closes, 4, 3);

        closes[4] = 1.0;

        assertThat(ContinuousFactorSignals.riskAdjustedMomentum(closes, 4, 3))
                .isCloseTo(before, within(1e-12));
    }

    @Test
    void insufficientInputReturnsNeutral() {
        assertThat(ContinuousFactorSignals.riskAdjustedMomentum(new double[]{100.0}, 1, 3)).isZero();
        assertThat(ContinuousFactorSignals.fundingCarry(new double[]{}, 0, 10)).isZero();
        assertThat(ContinuousFactorSignals.amihudIlliquidity(new double[]{100.0}, new double[]{10.0}, 1, 5)).isZero();
    }
}
