package com.mawai.wiibservice.agent.research.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Deflated Sharpe（Bailey & López de Prado 2014）：扣多重检验选择偏差 + 非正态后，真 Sharpe>0 的概率。 */
class DeflatedSharpeTest {

    private static final double EULER = 0.5772156649015329;

    @Test
    void expectedMaxSharpeIsZeroWhenFewerThanTwoTrials() {
        assertThat(DeflatedSharpe.expectedMaxSharpe(0.25, 1)).isEqualTo(0.0); // N=1 无可扣 → 退回 PSR vs 0
        assertThat(DeflatedSharpe.expectedMaxSharpe(0.25, 0)).isEqualTo(0.0);
    }

    @Test
    void expectedMaxSharpeScalesWithStandardDeviationOfTrialSharpes() {
        // SR0 ∝ √V：方差×4 → SR0×2
        double base = DeflatedSharpe.expectedMaxSharpe(0.04, 10);
        double quad = DeflatedSharpe.expectedMaxSharpe(0.16, 10);
        assertThat(quad).isCloseTo(2.0 * base, within(1e-9));
    }

    @Test
    void expectedMaxSharpeIncreasesWithMoreTrials() {
        // 试得越多 → 纯运气能达到的最大 Sharpe 越高
        double few = DeflatedSharpe.expectedMaxSharpe(0.04, 3);
        double mid = DeflatedSharpe.expectedMaxSharpe(0.04, 10);
        double many = DeflatedSharpe.expectedMaxSharpe(0.04, 100);
        assertThat(mid).isGreaterThan(few);
        assertThat(many).isGreaterThan(mid);
    }

    @Test
    void expectedMaxSharpeMatchesBaileyLopezDePradoFormula() {
        // 用已独立验证的 invCdf 逐项复核公式接线（系数/γ/参数无错）
        double v = 0.04;
        int n = 10;
        double expected = Math.sqrt(v) * ((1 - EULER) * NormalDistribution.invCdf(1.0 - 1.0 / n)
                + EULER * NormalDistribution.invCdf(1.0 - 1.0 / (n * Math.E)));
        assertThat(DeflatedSharpe.expectedMaxSharpe(v, n)).isCloseTo(expected, within(1e-12));
    }

    @Test
    void deflatedSharpeIsHalfWhenSharpeEqualsBenchmark() {
        // SR==SR0 → z=0 → Φ(0)=0.5（精确锚）
        SharpeStats s = new SharpeStats(0.1, 0.0, 3.0, 101);
        assertThat(DeflatedSharpe.deflatedSharpe(s, 0.1)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void deflatedSharpeAboveHalfWhenBeatingBenchmarkBelowWhenLosing() {
        SharpeStats s = new SharpeStats(0.1, 0.0, 3.0, 101);
        assertThat(DeflatedSharpe.deflatedSharpe(s, 0.0)).isGreaterThan(0.5);  // SR>SR0
        assertThat(DeflatedSharpe.deflatedSharpe(s, 0.5)).isLessThan(0.5);     // SR<SR0
    }

    @Test
    void deflatedSharpeMatchesHandComputedProbability() {
        // skew=0,kurt=3,SR=0.1,T=101,benchmark=0 → z=0.1·√100/√1.005=0.99751 → Φ≈0.84074
        SharpeStats s = new SharpeStats(0.1, 0.0, 3.0, 101);
        assertThat(DeflatedSharpe.deflatedSharpe(s, 0.0)).isCloseTo(0.84074, within(1e-3));
    }

    @Test
    void varianceOfSharpesIsSampleVarianceOfTrialSharpes() {
        // sharpe=[1,2,3,4,5] → 样本方差(n-1)=10/4=2.5（其余字段无关）
        SharpeStats[] trials = {
                new SharpeStats(1, 0, 0, 10), new SharpeStats(2, 0, 0, 10),
                new SharpeStats(3, 0, 0, 10), new SharpeStats(4, 0, 0, 10),
                new SharpeStats(5, 0, 0, 10)
        };
        assertThat(DeflatedSharpe.varianceOfSharpes(trials)).isCloseTo(2.5, within(1e-9));
    }
}
