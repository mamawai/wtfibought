package com.mawai.wiibservice.agent.research.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 标准正态 Φ / Φ⁻¹ 数值近似对拍已知值（DSR 全靠这两个函数）。 */
class NormalDistributionTest {

    @Test
    void cdfMatchesKnownStandardNormalValues() {
        assertThat(NormalDistribution.cdf(0.0)).isCloseTo(0.5, within(1e-9));
        assertThat(NormalDistribution.cdf(1.0)).isCloseTo(0.8413447460685429, within(1e-6));
        assertThat(NormalDistribution.cdf(-1.0)).isCloseTo(0.15865525393145707, within(1e-6));
        assertThat(NormalDistribution.cdf(1.959963984540054)).isCloseTo(0.975, within(1e-6));   // 双侧95%
        assertThat(NormalDistribution.cdf(2.5758293035489004)).isCloseTo(0.995, within(1e-6));  // 双侧99%
    }

    @Test
    void cdfSaturatesAtTails() {
        assertThat(NormalDistribution.cdf(-40.0)).isCloseTo(0.0, within(1e-9));
        assertThat(NormalDistribution.cdf(40.0)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void invCdfMatchesKnownQuantiles() {
        assertThat(NormalDistribution.invCdf(0.5)).isCloseTo(0.0, within(1e-9));
        assertThat(NormalDistribution.invCdf(0.975)).isCloseTo(1.959963984540054, within(1e-5));
        assertThat(NormalDistribution.invCdf(0.95)).isCloseTo(1.6448536269514722, within(1e-5));
        assertThat(NormalDistribution.invCdf(0.025)).isCloseTo(-1.959963984540054, within(1e-5));
    }

    @Test
    void invCdfIsInverseOfCdf() {
        for (double x : new double[]{-2.5, -1.0, -0.3, 0.0, 0.7, 1.5, 2.8}) {
            assertThat(NormalDistribution.invCdf(NormalDistribution.cdf(x))).isCloseTo(x, within(1e-6));
        }
    }
}
