package com.mawai.wiibservice.agent.research.factor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContinuousFactorBuilderTest {

    private static final ContinuousFactorParams PARAMS =
            new ContinuousFactorParams(3, 2, 3, 3, 2, 1, 3);

    @Test
    void buildWiresAllContinuousSignals() {
        double[] closes = {100.0, 110.0, 105.0, 130.0};
        double[] volumes = {10.0, 20.0, 30.0, 40.0};
        double[] funding = {0.0, 0.001, 0.002, 0.003};
        double[] btcReturns = {1.0, 2.0, 3.0, 4.0};
        double[] altReturns = {2.0, 4.0, 6.0, 9.0};

        ContinuousFactorVector v = ContinuousFactorBuilder.build(
                closes, volumes, funding, altReturns, btcReturns, 4, PARAMS);

        assertThat(v.riskAdjustedMomentum()).isEqualTo(
                ContinuousFactorSignals.riskAdjustedMomentum(closes, 4, PARAMS.momentumLookback()));
        assertThat(v.shortReversal()).isEqualTo(
                ContinuousFactorSignals.shortReversal(closes, 4, PARAMS.reversalLookback()));
        assertThat(v.fundingCarry()).isEqualTo(
                ContinuousFactorSignals.fundingCarry(funding, 4, PARAMS.fundingLookback()));
        assertThat(v.volumeZScore()).isEqualTo(
                ContinuousFactorSignals.volumeZScore(volumes, 4, PARAMS.volumeLookback()));
        assertThat(v.amihudIlliquidity()).isEqualTo(
                ContinuousFactorSignals.amihudIlliquidity(closes, volumes, 4, PARAMS.amihudLookback()));
        assertThat(v.residualMomentum()).isEqualTo(
                ContinuousFactorSignals.residualMomentum(altReturns, btcReturns, 4,
                        PARAMS.residualMomentumLookback(), PARAMS.betaWindow()));
    }

    @Test
    void buildDoesNotReadFutureRows() {
        double[] closes = {100.0, 110.0, 105.0, 130.0, 9999.0};
        double[] volumes = {10.0, 20.0, 30.0, 40.0, 9999.0};
        double[] funding = {0.0, 0.001, 0.002, 0.003, 9999.0};
        double[] btcReturns = {1.0, 2.0, 3.0, 4.0, 9999.0};
        double[] altReturns = {2.0, 4.0, 6.0, 9.0, -9999.0};

        ContinuousFactorVector before = ContinuousFactorBuilder.build(
                closes, volumes, funding, altReturns, btcReturns, 4, PARAMS);

        closes[4] = 1.0;
        volumes[4] = 1.0;
        funding[4] = -9999.0;
        btcReturns[4] = -9999.0;
        altReturns[4] = 9999.0;

        assertThat(ContinuousFactorBuilder.build(closes, volumes, funding, altReturns, btcReturns, 4, PARAMS))
                .isEqualTo(before);
    }

    @Test
    void buildReturnsNeutralVectorWhenInputsAreEmpty() {
        ContinuousFactorVector v = ContinuousFactorBuilder.build(
                new double[]{}, new double[]{}, new double[]{}, new double[]{}, new double[]{}, 0, PARAMS);

        assertThat(v).isEqualTo(new ContinuousFactorVector(0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
    }
}
