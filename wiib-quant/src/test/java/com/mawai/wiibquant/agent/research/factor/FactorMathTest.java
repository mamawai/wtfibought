package com.mawai.wiibquant.agent.research.factor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FactorMathTest {

    @Test
    void zScoreUsesSampleStandardDeviation() {
        assertThat(FactorMath.zScore(3.0, new double[]{1.0, 2.0, 3.0}))
                .isCloseTo(1.0, within(1e-12));
    }

    @Test
    void zScoreReturnsNeutralWhenWindowHasNoVariation() {
        assertThat(FactorMath.zScore(5.0, new double[]{5.0, 5.0, 5.0})).isZero();
        assertThat(FactorMath.zScore(5.0, new double[]{5.0})).isZero();
    }

    @Test
    void percentileRankUsesMidRankForTies() {
        assertThat(FactorMath.percentileRank(2.0, new double[]{1.0, 2.0, 2.0, 4.0}))
                .isCloseTo(0.5, within(1e-12));
        assertThat(FactorMath.percentileRank(3.0, new double[]{1.0, 2.0, 3.0, 4.0}))
                .isCloseTo(0.625, within(1e-12));
        assertThat(FactorMath.percentileRank(9.0, new double[]{}))
                .isCloseTo(0.5, within(1e-12));
    }

    @Test
    void rateOfChangeUsesLastAgainstLaggedValue() {
        assertThat(FactorMath.rateOfChange(new double[]{100.0, 110.0, 121.0}, 2))
                .isCloseTo(0.21, within(1e-12));
        assertThat(FactorMath.rateOfChange(new double[]{0.0, 1.0}, 1)).isZero();
        assertThat(FactorMath.rateOfChange(new double[]{100.0}, 1)).isZero();
    }

    @Test
    void logReturnRejectsNonPositivePrices() {
        assertThat(FactorMath.logReturn(110.0, 100.0)).isCloseTo(Math.log(1.1), within(1e-12));
        assertThat(FactorMath.logReturn(0.0, 100.0)).isZero();
        assertThat(FactorMath.logReturn(100.0, 0.0)).isZero();
    }

    @Test
    void winsorizeClipsToBounds() {
        assertThat(FactorMath.winsorize(5.0, 1.0, 3.0)).isEqualTo(3.0);
        assertThat(FactorMath.winsorize(-2.0, 1.0, 3.0)).isEqualTo(1.0);
        assertThat(FactorMath.winsorize(2.0, 1.0, 3.0)).isEqualTo(2.0);
    }

    @Test
    void squashIsTanhScaled() {
        assertThat(FactorMath.squash(2.0, 2.0)).isCloseTo(Math.tanh(1.0), within(1e-12));
        assertThat(FactorMath.squash(2.0, 0.0)).isCloseTo(Math.tanh(2.0), within(1e-12));
    }

    @Test
    void volatilityIsSampleStandardDeviation() {
        assertThat(FactorMath.volatility(new double[]{1.0, 2.0, 3.0}))
                .isCloseTo(1.0, within(1e-12));
        assertThat(FactorMath.volatility(new double[]{1.0})).isZero();
    }
}
