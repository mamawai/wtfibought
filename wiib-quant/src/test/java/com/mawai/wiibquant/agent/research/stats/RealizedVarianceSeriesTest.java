package com.mawai.wiibquant.agent.research.stats;

import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RealizedVarianceSeriesTest {

    @Test
    void perBarCloseVarianceSquaresLogReturns() {
        // closes 100→110→99：[0]=0，[1]=ln(110/100)²，[2]=ln(99/110)²
        List<KlineBar> series = List.of(close("100"), close("110"), close("99"));
        double r1 = Math.log(110.0 / 100.0);
        double r2 = Math.log(99.0 / 110.0);

        double[] v = RealizedVarianceSeries.perBarCloseVariance(series);

        assertThat(v).hasSize(3);
        assertThat(v[0]).isZero();
        assertThat(v[1]).isCloseTo(r1 * r1, within(1e-12));
        assertThat(v[2]).isCloseTo(r2 * r2, within(1e-12));
    }

    @Test
    void perBarGarmanKlassDelegatesToRangeVariancePerBar() {
        List<KlineBar> series = List.of(
                ohlc("100", "110", "90", "105"),
                ohlc("105", "120", "80", "100"));

        double[] v = RealizedVarianceSeries.perBarGarmanKlass(series);

        assertThat(v).hasSize(2);
        assertThat(v[0]).isCloseTo(RangeVariance.garmanKlass(100, 110, 90, 105), within(1e-12));
        assertThat(v[1]).isCloseTo(RangeVariance.garmanKlass(105, 120, 80, 100), within(1e-12));
    }

    @Test
    void horizonSumsWindowsTheBarsAfterDecisionClose() {
        // perBarVar=[10,1,2,3,4,5]，H=2：点 idx=0 → Σ[1..2]=3；点 idx=1 → Σ[2..3]=5（决策当根 idx 不计入）
        double[] perBar = {10, 1, 2, 3, 4, 5};

        double[] sums = RealizedVarianceSeries.horizonSums(perBar, List.of(0, 1), 2);

        assertThat(sums).hasSize(2);
        assertThat(sums[0]).isCloseTo(3.0, within(1e-12));
        assertThat(sums[1]).isCloseTo(5.0, within(1e-12));
    }

    static KlineBar close(String c) {
        BigDecimal v = new BigDecimal(c);
        return new KlineBar(0, 0, v, v, v, v, BigDecimal.ONE);
    }

    static KlineBar ohlc(String o, String h, String l, String c) {
        return new KlineBar(0, 0, new BigDecimal(o), new BigDecimal(h),
                new BigDecimal(l), new BigDecimal(c), BigDecimal.ONE);
    }
}
