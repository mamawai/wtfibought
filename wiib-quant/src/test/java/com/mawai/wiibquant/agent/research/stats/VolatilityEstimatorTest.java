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

    @Test
    void rollingSigmaUsesOnlyLastWindowReturns() {
        // returns: r1=ln(110/100), r2=ln(99/110)。window=1 只取最后一个收益 → |r2|；window=2 → 两者 RMS。
        List<KlineBar> bars = List.of(bar("100"), bar("110"), bar("99"));
        double r1 = Math.log(110.0 / 100.0);
        double r2 = Math.log(99.0 / 110.0);

        assertThat(VolatilityEstimator.rollingSigma(bars, 1)).isCloseTo(Math.abs(r2), within(1e-12));
        assertThat(VolatilityEstimator.rollingSigma(bars, 2))
                .isCloseTo(Math.sqrt((r1 * r1 + r2 * r2) / 2), within(1e-12));
    }

    @Test
    void rollingSigmaWindowBeyondAvailableUsesAllReturns() {
        // window 超过可用收益数 → 退化为全样本（即"常数/扩张 σ"用 window=MAX_VALUE 实现）。
        List<KlineBar> bars = List.of(bar("100"), bar("110"), bar("99"));

        assertThat(VolatilityEstimator.rollingSigma(bars, 100))
                .isCloseTo(VolatilityEstimator.rollingSigma(bars, 2), within(1e-12));
        assertThat(VolatilityEstimator.rollingSigma(bars, Integer.MAX_VALUE))
                .isCloseTo(VolatilityEstimator.rollingSigma(bars, 2), within(1e-12));
    }

    @Test
    void rollingSigmaConstantRatioEqualsAbsReturn() {
        // 恒定比率 1.01 → 任意窗口 RMS = |ln(1.01)|，且不会像 random-walk 单点那样塌到 0。
        List<KlineBar> bars = List.of(bar("100"), bar("101"), bar("102.01"), bar("103.0301"));

        assertThat(VolatilityEstimator.rollingSigma(bars, 3)).isCloseTo(Math.log(1.01), within(1e-9));
    }

    @Test
    void rollingSigmaLessThanTwoBarsIsZero() {
        assertThat(VolatilityEstimator.rollingSigma(List.of(bar("100")), 5)).isZero();
        assertThat(VolatilityEstimator.rollingSigma(List.of(), 5)).isZero();
    }

    static KlineBar bar(String close) {
        BigDecimal c = new BigDecimal(close);
        return new KlineBar(0, 0, c, c, c, c, BigDecimal.ONE);
    }
}
