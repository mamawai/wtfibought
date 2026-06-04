package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.factor.ContinuousFactorVector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContinuousFactorForecasterTest {

    @Test
    void untrainedForecasterStaysFlat() {
        Forecast f = ContinuousFactorForecaster.defaults().forecast(features(vector(2.0)));

        assertThat(f.direction()).isZero();
        assertThat(f.confidence()).isZero();
    }

    @Test
    void fitLearnsPositiveFactorRelation() {
        Forecaster trained = ContinuousFactorForecaster.defaults().fit(List.of(
                sample(-3.0, -0.03),
                sample(-2.0, -0.02),
                sample(-1.0, -0.01),
                sample(1.0, 0.01),
                sample(2.0, 0.02),
                sample(3.0, 0.03)
        ));

        assertThat(trained.forecast(features(vector(2.0))).direction()).isEqualTo(1);
        assertThat(trained.forecast(features(vector(-2.0))).direction()).isEqualTo(-1);
    }

    @Test
    void fitLearnsContrarianFactorRelation() {
        Forecaster trained = ContinuousFactorForecaster.defaults().fit(List.of(
                sample(-3.0, 0.03),
                sample(-2.0, 0.02),
                sample(-1.0, 0.01),
                sample(1.0, -0.01),
                sample(2.0, -0.02),
                sample(3.0, -0.03)
        ));

        assertThat(trained.forecast(features(vector(2.0))).direction()).isEqualTo(-1);
        assertThat(trained.forecast(features(vector(-2.0))).direction()).isEqualTo(1);
    }

    @Test
    void fitWithNoReturnVariationStaysFlat() {
        Forecaster trained = ContinuousFactorForecaster.defaults().fit(List.of(
                sample(-3.0, 0.0),
                sample(-2.0, 0.0),
                sample(-1.0, 0.0),
                sample(1.0, 0.0),
                sample(2.0, 0.0),
                sample(3.0, 0.0)
        ));

        assertThat(trained.forecast(features(vector(2.0))).direction()).isZero();
    }

    private static TrainingSample sample(double riskAdjustedMomentum, double realizedLongReturn) {
        return new TrainingSample(features(vector(riskAdjustedMomentum)), BigDecimal.valueOf(realizedLongReturn));
    }

    private static ResearchFeatures features(ContinuousFactorVector v) {
        return new ResearchFeatures(List.of(), 0.0, 50, 0.0, 0.0, v);
    }

    private static ContinuousFactorVector vector(double riskAdjustedMomentum) {
        return new ContinuousFactorVector(riskAdjustedMomentum, 0.0, 0.0, 0.0, 0.0, 0.0);
    }
}
