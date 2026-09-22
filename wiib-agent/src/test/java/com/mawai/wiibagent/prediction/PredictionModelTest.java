package com.mawai.wiibagent.prediction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 公平概率的数：进末分钟前的 σ、末分钟的锁定、两处衔接、Φ⁻¹、后劲修正的方向 */
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

    @Test
    void Φ反函数和Φ互逆() {
        for (double p : new double[]{0.01, 0.02, 0.1, 0.3, 0.5, 0.7, 0.9, 0.98, 0.99}) {
            assertThat(PredictionModel.p(PredictionModel.inverse(p))).isCloseTo(p, within(1e-6));
        }
        assertThat(PredictionModel.inverse(0.5)).isCloseTo(0.0, within(1e-9));
        assertThat(PredictionModel.inverse(0.8413)).isCloseTo(1.0, within(1e-3));
    }

    @Test
    void 后劲修正_顺着方向加_平盘不动_两头夹住() {
        // Φ(Φ⁻¹(0.7) + 0.3) = Φ(0.8244) ≈ 0.795
        assertThat(PredictionModel.tilted(0.7, 1.0, 1, 0.3)).isCloseTo(0.795, within(0.002));
        // 跌势里说还在推 → 更偏 DOWN
        assertThat(PredictionModel.tilted(0.3, 1.0, -1, 0.3)).isCloseTo(0.205, within(0.002));
        // 平盘不动
        assertThat(PredictionModel.tilted(0.6, 1.0, 0, 0.3)).isCloseTo(0.6, within(1e-6));
        // 0.999 夹到 0.99 再倾斜，不会炸
        assertThat(PredictionModel.tilted(0.999, -1.0, 1, 0.3)).isCloseTo(0.979, within(0.002));
    }
}
