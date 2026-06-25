package com.mawai.wiibquant.agent.research.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 逐期收益的 per-period Sharpe / skew / kurt 对拍手算精确值（DSR 的输入矩）。 */
class SharpeStatsTest {

    @Test
    void symmetricSeriesHasZeroSkewAndKnownSharpeKurtosis() {
        // [1,2,3,4,5]: μ=3, 样本sd=√2.5 → SR=3/√2.5; 对称→skew=0; kurt(Pearson)=1.7
        SharpeStats s = SharpeStats.of(new double[]{1, 2, 3, 4, 5});
        assertThat(s.sharpe()).isCloseTo(1.8973665961010275, within(1e-9));
        assertThat(s.skewness()).isCloseTo(0.0, within(1e-9));
        assertThat(s.kurtosis()).isCloseTo(1.7, within(1e-9));
        assertThat(s.n()).isEqualTo(5);
    }

    @Test
    void asymmetricSeriesHasPositiveSkewAndKnownMoments() {
        // [0,0,0,0,10]: μ=2, 样本sd=√20 → SR=2/√20; 右尾→skew=+1.5; kurt=3.25
        SharpeStats s = SharpeStats.of(new double[]{0, 0, 0, 0, 10});
        assertThat(s.sharpe()).isCloseTo(0.4472135954999579, within(1e-9));
        assertThat(s.skewness()).isCloseTo(1.5, within(1e-9));
        assertThat(s.kurtosis()).isCloseTo(3.25, within(1e-9));
        assertThat(s.n()).isEqualTo(5);
    }

    @Test
    void constantSeriesIsDegenerateAndReturnsZeros() {
        SharpeStats s = SharpeStats.of(new double[]{5, 5, 5, 5});
        assertThat(s.sharpe()).isEqualTo(0.0);   // sd=0 → SR 无定义，退化为 0
        assertThat(s.n()).isEqualTo(4);
    }

    @Test
    void tooShortSeriesReturnsZeros() {
        SharpeStats s = SharpeStats.of(new double[]{0.01});
        assertThat(s.sharpe()).isEqualTo(0.0);   // n<2 无样本方差
        assertThat(s.n()).isEqualTo(1);
    }
}
