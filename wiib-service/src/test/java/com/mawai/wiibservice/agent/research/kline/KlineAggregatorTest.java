package com.mawai.wiibservice.agent.research.kline;
import com.mawai.wiibcommon.market.KlineBar;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KlineAggregatorTest {

    // 用 3 分钟(180_000ms)作为聚合周期，保持测试可读；函数对任意 horizonMillis 通用。
    private static final long THREE_MIN = 3 * 60_000L;

    @Test
    void aggregatesByEpochBucketWithCorrectOhlcv() {
        // GIVEN: 5 根 1m bar，前 3 根落在 bucket0 [0,180000)，后 2 根落在 bucket1
        List<KlineBar> oneMin = List.of(
                bar(0,        "100", "105", "99",  "104", "10"),
                bar(60_000,   "104", "108", "103", "107", "12"),
                bar(120_000,  "107", "110", "106", "109", "8"),
                bar(180_000,  "109", "111", "108", "110", "5"),
                bar(240_000,  "110", "112", "109", "111", "7"));

        // WHEN
        List<KlineBar> agg = KlineAggregator.aggregate(oneMin, THREE_MIN);

        // THEN: 2 个聚合 bar，OHLCV 正确，openTime 对齐到 bucket 边界
        assertThat(agg).hasSize(2);

        KlineBar b0 = agg.get(0);
        assertThat(b0.openTime()).isEqualTo(0L);
        assertThat(b0.open()).isEqualByComparingTo("100");   // 首根 open
        assertThat(b0.high()).isEqualByComparingTo("110");   // 三根最高
        assertThat(b0.low()).isEqualByComparingTo("99");     // 三根最低
        assertThat(b0.close()).isEqualByComparingTo("109");  // 末根 close
        assertThat(b0.volume()).isEqualByComparingTo("30");  // 量求和

        KlineBar b1 = agg.get(1);
        assertThat(b1.openTime()).isEqualTo(180_000L);
        assertThat(b1.open()).isEqualByComparingTo("109");
        assertThat(b1.high()).isEqualByComparingTo("112");
        assertThat(b1.low()).isEqualByComparingTo("108");
        assertThat(b1.close()).isEqualByComparingTo("111");
        assertThat(b1.volume()).isEqualByComparingTo("12");
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(KlineAggregator.aggregate(List.of(), THREE_MIN)).isEmpty();
    }

    static KlineBar bar(long openTime, String o, String h, String l, String c, String v) {
        return new KlineBar(openTime, openTime + 59_999L,
                new BigDecimal(o), new BigDecimal(h), new BigDecimal(l),
                new BigDecimal(c), new BigDecimal(v));
    }
}
