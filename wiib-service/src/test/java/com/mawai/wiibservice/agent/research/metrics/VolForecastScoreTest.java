package com.mawai.wiibservice.agent.research.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** A2 波动率预测评分：完美预测零损失 / 偏估增损失 / 空 / 长度校验。 */
class VolForecastScoreTest {

    @Test
    void perfectForecastHasZeroLoss() {
        // 预测 σ = 实现 |r| → 预测方差=实现方差 → MSE=0, QLIKE=0
        double[] pred = {0.01, 0.02, 0.03};
        double[] realized = {0.01, -0.02, 0.03};

        VolForecastScore s = VolForecastScore.evaluate(pred, realized);

        assertThat(s.n()).isEqualTo(3);
        assertThat(s.mse()).isCloseTo(0.0, within(1e-12));
        assertThat(s.qlike()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void overforecastIncreasesLoss() {
        double[] pred = {0.1, 0.1, 0.1};        // 预测高波动
        double[] realized = {0.01, 0.01, 0.01}; // 实际低波动

        VolForecastScore s = VolForecastScore.evaluate(pred, realized);

        assertThat(s.mse()).isGreaterThan(0.0);
        assertThat(s.qlike()).isGreaterThan(0.0);
    }

    @Test
    void emptyIsZero() {
        VolForecastScore s = VolForecastScore.evaluate(new double[0], new double[0]);

        assertThat(s.n()).isZero();
        assertThat(s.mse()).isZero();
        assertThat(s.qlike()).isZero();
    }

    @Test
    void qlikeLossesExposePerPointLossForComparison() {
        double[] losses = VolForecastScore.qlikeLosses(new double[]{0.01, 0.02}, new double[]{0.01, -0.04});

        assertThat(losses).hasSize(2);
        assertThat(losses[0]).isCloseTo(0.0, within(1e-9));
        assertThat(losses[1]).isGreaterThan(0.0);
    }

    @Test
    void mismatchedLengthsRejected() {
        assertThatThrownBy(() -> VolForecastScore.evaluate(new double[]{0.01}, new double[]{0.01, 0.02}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
