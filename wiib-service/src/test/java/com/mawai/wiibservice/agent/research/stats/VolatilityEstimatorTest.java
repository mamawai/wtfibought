package com.mawai.wiibservice.agent.research.stats;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VolatilityEstimatorTest {

    @Test
    void constantLogReturnGivesThatReturnAsVol() {
        // 收盘价恒定比率 1.01 → 每期对数收益 = ln(1.01) 恒定 → EWMA 方差 = r² → vol = |r|（与 lambda 无关）
        List<KlineBar> bars = List.of(
                bar("100"), bar("101"), bar("102.01"), bar("103.0301"));

        double vol = VolatilityEstimator.ewmaVolatility(bars, 0.94);

        assertThat(vol).isCloseTo(Math.log(1.01), within(1e-6)); // ≈ 0.00995033
    }

    @Test
    void lessThanTwoBarsGivesZero() {
        assertThat(VolatilityEstimator.ewmaVolatility(List.of(bar("100")), 0.94)).isZero();
        assertThat(VolatilityEstimator.ewmaVolatility(List.of(), 0.94)).isZero();
    }

    static KlineBar bar(String close) {
        BigDecimal c = new BigDecimal(close);
        return new KlineBar(0, 0, c, c, c, c, BigDecimal.ONE);
    }
}
