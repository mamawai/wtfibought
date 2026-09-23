package com.mawai.wiibagent.prediction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 公平概率的数：进末分钟前的 σ、末分钟的锁定、两处衔接 */
class PredictionModelTest {

    private static final double SIGMA = 0.03;   // 1 分钟典型波动 0.03%

    @Test
    void 进末分钟前_噪声按到末分钟的秒数加二十秒算() {
        // 剩 240 秒：σ_eff = 0.03 × √(200/60) ≈ 0.0548，领先 0.05% → z ≈ 0.913
        double z = PredictionModel.z(0.05, null, SIGMA, 240);
        assertThat(z).isCloseTo(0.05 / (SIGMA * Math.sqrt(200.0 / 60)), within(1e-9));
        assertThat(PredictionModel.p(z)).isCloseTo(0.819, within(0.002));
        // 同样的领先剩 90 秒更稳
        assertThat(PredictionModel.z(0.05, null, SIGMA, 90)).isGreaterThan(z);
        // 方向
        assertThat(PredictionModel.z(-0.05, null, SIGMA, 240)).isCloseTo(-z, within(1e-9));
    }

    @Test
    void 末分钟_已走过的部分锁定_剩三十秒像样的领先几乎定了() {
        // 剩 30 秒，已走过 30 秒的均价领先 0.03%，现价也领先 0.03%
        double z = PredictionModel.z(0.03, 0.03, SIGMA, 30);
        assertThat(z).isGreaterThan(4.5);
        assertThat(PredictionModel.p(z)).isGreaterThan(0.999);
        // 已走过部分领先、现价已经跌回开盘价：锁定的一半还在撑
        double half = PredictionModel.z(0.0, 0.03, SIGMA, 30);
        assertThat(half).isGreaterThan(2).isLessThan(z);
    }

    @Test
    void 剩六十秒两条公式衔接() {
        double before = PredictionModel.z(0.03, null, SIGMA, 60);
        double inside = PredictionModel.z(0.03, 0.03, SIGMA, 59);
        assertThat(before).isCloseTo(0.03 / (SIGMA * Math.sqrt(20.0 / 60)), within(1e-9));
        assertThat(inside).isCloseTo(before, within(0.05));
    }
}
