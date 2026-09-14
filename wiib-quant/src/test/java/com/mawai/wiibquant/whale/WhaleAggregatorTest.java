package com.mawai.wiibquant.whale;

import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.Position;
import com.mawai.wiibquant.whale.WhaleAggregator.CoinSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聚合：加权均价、中位数、前一名占比、分桶边界、仓位层过滤、liquidationPx 为空、空集合。
 */
class WhaleAggregatorTest {

    private static final BigDecimal MIN = new BigDecimal("100000");
    private static final BigDecimal MARK = new BigDecimal("80000");
    private static final BigDecimal OI = new BigDecimal("36000");

    private static Position h(String szi, String entry, String value, String liq, String upnl) {
        return new Position("BTC", new BigDecimal(szi), new BigDecimal(entry), new BigDecimal(value), 10,
                liq == null ? null : new BigDecimal(liq), new BigDecimal(upnl));
    }

    private static CoinSnapshot agg(Position... ps) {
        return WhaleAggregator.aggregate("BTC", MARK, OI, List.of(ps), MIN);
    }

    @Test
    void 加权均价按币数量_中位数按地址_前一名占比_浮盈亏求和() {
        // 多头：2 个币 @100 与 6 个币 @200：加权 175，中位数 150，名义 200k/600k → 前一名 0.75
        CoinSnapshot s = agg(
                h("2", "100", "200000", "90", "1000"),
                h("6", "200", "600000", "150", "-400"),
                h("-1", "300", "300000", "350", "50"));

        assertThat(s.longSide().count()).isEqualTo(2);
        assertThat(s.longSide().notional()).isEqualByComparingTo("800000");
        assertThat(s.longSide().wavgEntry()).isEqualByComparingTo("175");
        assertThat(s.longSide().medianEntry()).isEqualByComparingTo("150");
        assertThat(s.longSide().top1Share()).isEqualByComparingTo("0.75");
        assertThat(s.longSide().upnl()).isEqualByComparingTo("600");
        assertThat(s.shortSide().count()).isEqualTo(1);
        assertThat(s.shortSide().wavgEntry()).isEqualByComparingTo("300");
        assertThat(s.shortSide().medianEntry()).isEqualByComparingTo("300");
        assertThat(s.shortSide().top1Share()).isEqualByComparingTo("1");
        assertThat(s.hlOpenInterest()).isEqualByComparingTo("2880000000");
        assertThat(s.price()).isEqualByComparingTo(MARK);
    }

    @Test
    void 中位数_奇数个取中间那个() {
        CoinSnapshot s = agg(
                h("1", "100", "200000", null, "0"),
                h("1", "900", "200000", null, "0"),
                h("1", "300", "200000", null, "0"));

        assertThat(s.longSide().medianEntry()).isEqualByComparingTo("300");
    }

    @Test
    void 仓位层过滤_名义不够的不算_刚好够的算() {
        CoinSnapshot s = agg(
                h("1", "100", "99999.99", null, "0"),
                h("1", "200", "100000", null, "0"));

        assertThat(s.longSide().count()).isEqualTo(1);
        assertThat(s.longSide().wavgEntry()).isEqualByComparingTo("200");
    }

    @Test
    void 分桶_宽度跟标记价_下界向下取整_多空各占一列_只存非空桶_名义额到分() {
        // 开仓价桶宽 80000 × 0.25% = 200：78553.9 → 78400；78600 与 78799.9 → 78600
        CoinSnapshot s = agg(
                h("1", "78553.9", "200000.005", "70000", "0"),
                h("1", "78600", "300000.0", "70399", "0"),
                h("-1", "78799.9", "500000", "90000", "0"));

        // HL 给 10 位小数，桶里名义额四舍五入到分，整数不带 .0
        assertThat(s.entryBucketsJson()).isEqualTo("{\"width\":200,\"buckets\":[[78400,200000.01,0],[78600,300000,500000]]}");
        // 强平价桶宽 80000 × 0.5% = 400：70000 与 70399 同桶，90000 是空头
        assertThat(s.liqBucketsJson()).isEqualTo("{\"width\":400,\"buckets\":[[70000,500000.01,0],[90000,0,500000]]}");
    }

    @Test
    void 强平价为空_进多空统计_不进强平桶() {
        CoinSnapshot s = agg(
                h("1", "78000", "200000", null, "0"),
                h("-1", "78000", "300000", "85000", "0"));

        assertThat(s.longSide().count()).isEqualTo(1);
        assertThat(s.shortSide().count()).isEqualTo(1);
        assertThat(s.entryBucketsJson()).isEqualTo("{\"width\":200,\"buckets\":[[78000,200000,300000]]}");
        assertThat(s.liqBucketsJson()).isEqualTo("{\"width\":400,\"buckets\":[[84800,0,300000]]}");
    }

    @Test
    void 空集合_两侧count为0其余null_桶为空() {
        CoinSnapshot s = agg();

        assertThat(s.longSide().count()).isZero();
        assertThat(s.longSide().notional()).isEqualByComparingTo("0");
        assertThat(s.longSide().wavgEntry()).isNull();
        assertThat(s.longSide().medianEntry()).isNull();
        assertThat(s.longSide().top1Share()).isNull();
        assertThat(s.longSide().upnl()).isNull();
        assertThat(s.shortSide().count()).isZero();
        assertThat(s.entryBucketsJson()).isEqualTo("{\"width\":200,\"buckets\":[]}");
        assertThat(s.liqBucketsJson()).isEqualTo("{\"width\":400,\"buckets\":[]}");
        assertThat(s.hlOpenInterest()).isEqualByComparingTo("2880000000");
    }

    @Test
    void 小价币_桶宽与下界不丢精度() {
        // DOGE 0.084328 × 0.25% = 0.00021082
        CoinSnapshot s = WhaleAggregator.aggregate("DOGE", new BigDecimal("0.084328"), new BigDecimal("800000000"),
                List.of(new Position("DOGE", new BigDecimal("2000000"), new BigDecimal("0.0851"),
                        new BigDecimal("168656"), 5, null, BigDecimal.ZERO)), MIN);

        // floor(0.0851 / 0.00021082) = 403 → 403 × 0.00021082 = 0.08496046
        assertThat(s.entryBucketsJson()).isEqualTo("{\"width\":0.00021082,\"buckets\":[[0.08496046,168656,0]]}");
        assertThat(s.hlOpenInterest()).isEqualByComparingTo("67462400");
    }
}
