package com.mawai.wiibquant.agent.quant.node;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * BuildFeaturesBuilder 纯解析函数的行为锚定：这些函数直接吃 Binance/alternative.me 原始 JSON，
 * 上游字段变更/脏数据时靠这里第一时间红。
 */
class BuildFeaturesBuilderTest {

    // ---- calcLsrExtreme: 多空比窗口百分位（各币中枢不同，不用固定阈值）----

    @Test
    void lsrExtremeNullOrEmptyReturnsZero() {
        assertThat(BuildFeaturesBuilder.calcLsrExtreme(null)).isZero();
        assertThat(BuildFeaturesBuilder.calcLsrExtreme("[]")).isZero();
        assertThat(BuildFeaturesBuilder.calcLsrExtreme("not json")).isZero();
    }

    @Test
    void lsrExtremeFewerThanTwoValidRatiosReturnsZero() {
        // 无效项（<=0、非数值）被过滤后不足 2 个
        assertThat(BuildFeaturesBuilder.calcLsrExtreme(
                "[{\"longShortRatio\":1.5},{\"longShortRatio\":-1},{\"longShortRatio\":0}]")).isZero();
    }

    @Test
    void lsrExtremeLatestAtTopOfWindowIsPositive() {
        // ratios=[1.0,1.2,1.5,2.0]，latest=2.0：less=3 equal=1 → pct=0.875 → (0.875-0.5)*2=0.75
        String json = "[{\"longShortRatio\":1.0},{\"longShortRatio\":1.2},{\"longShortRatio\":1.5},{\"longShortRatio\":2.0}]";
        assertThat(BuildFeaturesBuilder.calcLsrExtreme(json)).isEqualTo(0.75, offset(1e-9));
    }

    @Test
    void lsrExtremeLatestAtBottomOfWindowIsNegative() {
        // latest=1.0 是窗口最小值：less=0 equal=1 → pct=0.125 → -0.75
        String json = "[{\"longShortRatio\":2.0},{\"longShortRatio\":1.5},{\"longShortRatio\":1.2},{\"longShortRatio\":1.0}]";
        assertThat(BuildFeaturesBuilder.calcLsrExtreme(json)).isEqualTo(-0.75, offset(1e-9));
    }

    // ---- calcFundingTrend: [trend, extreme]，除数 0.0003 归一化 ----

    @Test
    void fundingTrendNullBlankOrShortReturnsZeros() {
        assertThat(BuildFeaturesBuilder.calcFundingTrend(null)).containsExactly(0, 0);
        assertThat(BuildFeaturesBuilder.calcFundingTrend(" ")).containsExactly(0, 0);
        assertThat(BuildFeaturesBuilder.calcFundingTrend(
                "[{\"fundingRate\":0.0001},{\"fundingRate\":0.0002}]")).containsExactly(0, 0);
    }

    @Test
    void fundingTrendRisingRates() {
        // older=[0.0001,0.0001] recent=[0.0004,0.0004]：trend=(0.0004-0.0001)/0.0003=1.0
        // totalAvg=0.00025，latest=0.0004 → extreme=(0.00015)/0.0003=0.5
        String json = "[{\"fundingRate\":0.0001},{\"fundingRate\":0.0001},{\"fundingRate\":0.0004},{\"fundingRate\":0.0004}]";
        double[] out = BuildFeaturesBuilder.calcFundingTrend(json);
        assertThat(out[0]).isEqualTo(1.0, offset(1e-9));
        assertThat(out[1]).isEqualTo(0.5, offset(1e-9));
    }

    @Test
    void fundingTrendClampsToUnitRange() {
        String json = "[{\"fundingRate\":-0.01},{\"fundingRate\":-0.01},{\"fundingRate\":0.01},{\"fundingRate\":0.01}]";
        double[] out = BuildFeaturesBuilder.calcFundingTrend(json);
        assertThat(out[0]).isEqualTo(1.0);
        assertThat(out[1]).isEqualTo(1.0);
    }

    // ---- calcOiChangeRate: 最早 vs 最新 OI ----

    @Test
    void oiChangeRateGrowthAndShrink() {
        String grow = "[{\"sumOpenInterest\":100},{\"sumOpenInterest\":150}]";
        assertThat(BuildFeaturesBuilder.calcOiChangeRate(grow)).isEqualTo(0.5, offset(1e-9));
        String shrink = "[{\"sumOpenInterest\":100},{\"sumOpenInterest\":80}]";
        assertThat(BuildFeaturesBuilder.calcOiChangeRate(shrink)).isEqualTo(-0.2, offset(1e-9));
    }

    @Test
    void oiChangeRateDegenerateInputsReturnZero() {
        assertThat(BuildFeaturesBuilder.calcOiChangeRate(null)).isZero();
        assertThat(BuildFeaturesBuilder.calcOiChangeRate("[{\"sumOpenInterest\":100}]")).isZero();
        // 最早值为 0 防除零
        assertThat(BuildFeaturesBuilder.calcOiChangeRate(
                "[{\"sumOpenInterest\":0},{\"sumOpenInterest\":100}]")).isZero();
    }

    // ---- calcFundingDeviation: (rate-0.0001)/0.0003 ----

    @Test
    void fundingDeviationBaselineIsZeroAndScalesLinearly() {
        assertThat(BuildFeaturesBuilder.calcFundingDeviation("{\"lastFundingRate\":0.0001}")).isZero();
        assertThat(BuildFeaturesBuilder.calcFundingDeviation("{\"lastFundingRate\":0.0004}"))
                .isEqualTo(1.0, offset(1e-9));
        assertThat(BuildFeaturesBuilder.calcFundingDeviation(null)).isZero();
    }

    // ---- calcBidAskImbalance: 前5档买卖量差 ----

    @Test
    void bidAskImbalanceOneSidedBook() {
        String bidsOnly = "{\"bids\":[[\"100\",\"5\"],[\"99\",\"3\"]],\"asks\":[]}";
        assertThat(BuildFeaturesBuilder.calcBidAskImbalance(bidsOnly)).isEqualTo(1.0);
        String asksOnly = "{\"bids\":[],\"asks\":[[\"101\",\"4\"]]}";
        assertThat(BuildFeaturesBuilder.calcBidAskImbalance(asksOnly)).isEqualTo(-1.0);
        assertThat(BuildFeaturesBuilder.calcBidAskImbalance(null)).isZero();
    }

    @Test
    void bidAskImbalanceOnlyCountsTopFiveLevels() {
        // bids 第6档的 1000 不计入：bid=5x1=5, ask=5 → 0
        String json = "{\"bids\":[[\"1\",\"1\"],[\"1\",\"1\"],[\"1\",\"1\"],[\"1\",\"1\"],[\"1\",\"1\"],[\"1\",\"1000\"]],"
                + "\"asks\":[[\"1\",\"5\"]]}";
        assertThat(BuildFeaturesBuilder.calcBidAskImbalance(json)).isZero();
    }

    // ---- calcBasisBps: 期现基差 ----

    @Test
    void basisBpsPositiveWhenFuturesAboveSpot() {
        assertThat(BuildFeaturesBuilder.calcBasisBps(new BigDecimal("10100"), new BigDecimal("10000")))
                .isEqualTo(100.0);
        assertThat(BuildFeaturesBuilder.calcBasisBps(null, new BigDecimal("10000"))).isZero();
        assertThat(BuildFeaturesBuilder.calcBasisBps(new BigDecimal("10100"), BigDecimal.ZERO)).isZero();
    }

    // ---- calcFearGreed / mapFearGreedLabel ----

    @Test
    void fearGreedParsesAlternativeMePayload() {
        assertThat(BuildFeaturesBuilder.calcFearGreed("{\"data\":[{\"value\":73}]}")).isEqualTo(73);
        assertThat(BuildFeaturesBuilder.calcFearGreed("{}")).isEqualTo(-1);
        assertThat(BuildFeaturesBuilder.calcFearGreed(null)).isEqualTo(-1);
    }

    @Test
    void fearGreedLabelBoundaries() {
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(-1)).isEqualTo("UNKNOWN");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(24)).isEqualTo("EXTREME_FEAR");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(25)).isEqualTo("FEAR");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(44)).isEqualTo("FEAR");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(45)).isEqualTo("NEUTRAL");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(55)).isEqualTo("NEUTRAL");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(56)).isEqualTo("GREED");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(74)).isEqualTo("GREED");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(75)).isEqualTo("EXTREME_GREED");
    }

    // ---- calcTrendBias: 前半段 vs 后半段字段均值差 ----

    @Test
    void trendBiasComparesHalfWindowAverages() {
        // older=[10,10] recent=[20,20]，divisor=10 → clamp(10/10)=1
        String json = "[{\"v\":10},{\"v\":10},{\"v\":20},{\"v\":20}]";
        assertThat(BuildFeaturesBuilder.calcTrendBias(json, "v", 10)).isEqualTo(1.0);
        // divisor 放大 → 未触 clamp
        assertThat(BuildFeaturesBuilder.calcTrendBias(json, "v", 40)).isEqualTo(0.25, offset(1e-9));
        // 不足 4 条 → 0
        assertThat(BuildFeaturesBuilder.calcTrendBias("[{\"v\":1},{\"v\":2},{\"v\":3}]", "v", 10)).isZero();
    }
}
