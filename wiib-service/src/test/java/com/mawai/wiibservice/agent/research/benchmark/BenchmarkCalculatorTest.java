package com.mawai.wiibservice.agent.research.benchmark;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BenchmarkCalculatorTest {

    @Test
    void buyAndHoldIsLastOverFirstCloseMinusOne() {
        List<KlineBar> bars = List.of(barClose("100"), barClose("105"), barClose("110"));
        assertThat(BenchmarkCalculator.buyAndHoldReturn(bars)).isEqualByComparingTo("0.1"); // 110/100-1
    }

    @Test
    void buyAndHoldTooFewBarsIsZero() {
        assertThat(BenchmarkCalculator.buyAndHoldReturn(List.of(barClose("100")))).isEqualByComparingTo("0");
    }

    @Test
    void permutationPercentileMatchesTheoreticalFraction() {
        // positions=[1,0,0], returns=[0.05,-0.01,-0.01] → realReturn=0.05（最大）
        // 排列把那个"1"等概率落到 3 个槽：仅落槽0时=0.05（不<real），其余 2/3 落到 -0.01(<real)
        // → 分位 ≈ 2/3 = 0.667
        double pct = BenchmarkCalculator.permutationPercentile(
                List.of(1, 0, 0),
                List.of(new BigDecimal("0.05"), new BigDecimal("-0.01"), new BigDecimal("-0.01")),
                20000, 42L);
        assertThat(pct).isCloseTo(0.667, within(0.03));
    }

    static KlineBar barClose(String close) {
        BigDecimal c = new BigDecimal(close);
        return new KlineBar(0, 0, c, c, c, c, BigDecimal.ONE);
    }
}
