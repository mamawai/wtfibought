package com.mawai.wiibquant.agent.research.stats;

import com.mawai.wiibcommon.market.KlineBar;
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

    @Test
    void realizedVolatilityAccumulatesSquaredReturnsOverPath() {
        // 恒定比率 1.01、两期收益：已实现波动 = sqrt(Σr²) = sqrt(2)·ln(1.01)（整段兑现，非单期）
        List<KlineBar> bars = List.of(bar("100"), bar("101"), bar("102.01"));

        double rv = VolatilityEstimator.realizedVolatility(bars);

        assertThat(rv).isCloseTo(Math.sqrt(2) * Math.log(1.01), within(1e-9));
    }

    @Test
    void realizedVolatilityLessThanTwoBarsGivesZero() {
        assertThat(VolatilityEstimator.realizedVolatility(List.of(bar("100")))).isZero();
        assertThat(VolatilityEstimator.realizedVolatility(List.of())).isZero();
    }

    @Test
    void realizedVolatilityIsHorizonScaledNotPerBar() {
        // horizon 口径：恒定收益下 realized = sqrt(n)·|r|，远大于 per-bar ewmaVol = |r|。
        // 这正是 RegimeLabeler SHOCK 必须用的口径——path 整段兑现波动 vs 单期 baseline，同量纲才可比。
        List<KlineBar> bars = List.of(bar("100"), bar("101"), bar("102.01"), bar("103.0301"));

        double rv = VolatilityEstimator.realizedVolatility(bars);
        double perBar = VolatilityEstimator.ewmaVolatility(bars, 0.94);

        assertThat(rv).isCloseTo(Math.sqrt(3) * Math.log(1.01), within(1e-9)); // 3 期收益
        assertThat(rv).isGreaterThan(perBar);
    }

    static KlineBar bar(String close) {
        BigDecimal c = new BigDecimal(close);
        return new KlineBar(0, 0, c, c, c, c, BigDecimal.ONE);
    }
}
