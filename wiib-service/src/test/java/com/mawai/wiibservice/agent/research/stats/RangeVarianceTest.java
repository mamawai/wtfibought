package com.mawai.wiibservice.agent.research.stats;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RangeVarianceTest {

    @Test
    void flatBarGivesZeroVariance() {
        // open=high=low=close → ln(H/L)=0 且 ln(C/O)=0 → GK 方差=0
        assertThat(RangeVariance.garmanKlass(100, 100, 100, 100)).isZero();
    }

    @Test
    void garmanKlassMatchesClosedForm() {
        // GK = 0.5·(ln(H/L))² − (2ln2−1)·(ln(C/O))²
        double lnHL = Math.log(110.0 / 90.0);
        double lnCO = Math.log(105.0 / 100.0);
        double expected = 0.5 * lnHL * lnHL - (2 * Math.log(2) - 1) * lnCO * lnCO;

        double gk = RangeVariance.garmanKlass(100, 110, 90, 105);

        assertThat(gk).isCloseTo(expected, within(1e-12));
        assertThat(gk).isCloseTo(0.0192148, within(1e-6)); // 钉死数值，防常数/符号错
    }

    @Test
    void closeEqualsOpenLeavesOnlyRangeTerm() {
        // C=O → ln(C/O)=0 → GK 退化为纯极差项 0.5·(ln(H/L))²
        double lnHL = Math.log(120.0 / 80.0);
        double expected = 0.5 * lnHL * lnHL;

        assertThat(RangeVariance.garmanKlass(100, 120, 80, 100)).isCloseTo(expected, within(1e-12));
    }

    @Test
    void nonPositivePriceGivesZero() {
        // 价格非正 → 对数发散，防御性返回 0（真实 K 线价格恒正，这是兜底）
        assertThat(RangeVariance.garmanKlass(100, 110, 0, 105)).isZero();
        assertThat(RangeVariance.garmanKlass(0, 0, 0, 0)).isZero();
        assertThat(RangeVariance.garmanKlass(100, 110, -5, 105)).isZero();
    }
}
