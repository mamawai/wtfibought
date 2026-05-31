package com.mawai.wiibservice.agent.research.label;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TripleBarrierLabelerTest {

    // entry=100000, k=1.5, sigma=0.01 → upper=101500, lower=98500
    private static final BigDecimal ENTRY = new BigDecimal("100000");
    private static final double K = 1.5;
    private static final double SIGMA = 0.01;

    @Test
    void upperTouchedFirstGivesUpper() {
        List<KlineBar> path = List.of(
                bar("101000", "99000", "100000"),   // 都没触
                bar("102000", "100000", "101800"));  // high≥101500 → 上栏
        assertThat(TripleBarrierLabeler.label(ENTRY, K, SIGMA, path)).isEqualTo(BarrierLabel.UPPER);
    }

    @Test
    void lowerTouchedFirstGivesLower() {
        List<KlineBar> path = List.of(
                bar("101000", "98000", "98200"));    // low≤98500 → 下栏
        assertThat(TripleBarrierLabeler.label(ENTRY, K, SIGMA, path)).isEqualTo(BarrierLabel.LOWER);
    }

    @Test
    void neitherTouchedGivesVertical() {
        List<KlineBar> path = List.of(
                bar("101000", "99000", "100500"),
                bar("101200", "99500", "100800"));
        assertThat(TripleBarrierLabeler.label(ENTRY, K, SIGMA, path)).isEqualTo(BarrierLabel.VERTICAL);
    }

    @Test
    void sameBarBothTouchedResolvedByClose() {
        // 同一根同时穿上下栏 → 用 close 相对 entry 决定（close≥entry → 视作先上）
        List<KlineBar> path = List.of(bar("102000", "98000", "100500"));
        assertThat(TripleBarrierLabeler.label(ENTRY, K, SIGMA, path)).isEqualTo(BarrierLabel.UPPER);
    }

    static KlineBar bar(String high, String low, String close) {
        return new KlineBar(0, 0, new BigDecimal(close),
                new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), BigDecimal.ONE);
    }
}
