package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibcommon.market.DepthStreamCache.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 扫盘口：卖盘 100.0×1、100.5×2，买盘 99.5×1、99.0×2；兜底价 99.8。
 * 快照比最新成交旧才封顶（合约用 E 对 T，现货用两边 feed 收到的时刻）；1 秒内没收到快照、或扫出错，用兜底价；
 * 触发价是 1 秒前的旧价，直接按它成交。
 */
class BookFillPricerTest {

    private static final String LEVELS = "\"bids\":[[\"99.5\",\"1\"],[\"99.0\",\"2\"]],"
            + "\"asks\":[[\"100.0\",\"1\"],[\"100.5\",\"2\"]]";
    private static final BigDecimal FALLBACK = new BigDecimal("99.8");

    private DepthStreamCache depth;
    private StreamOperations<String, Object, Object> stream;
    private BookFillPricer pricer;
    private long now;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        depth = mock(DepthStreamCache.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stream = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(stream);
        pricer = new BookFillPricer(depth, redis);
        now = System.currentTimeMillis();
    }

    /** 合约快照：feed 收到时刻 receivedAt，交易所事件时间 E */
    private void futuresBook(long receivedAt, long e) {
        when(depth.snapshot("BTCUSDT")).thenReturn(new Snapshot(receivedAt, "{\"E\":" + e + "," + LEVELS + "}"));
    }

    @SuppressWarnings("unchecked")
    private void latestTrade(String key, String price, long ts, long receivedAt) {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("ts", Long.toString(ts));
        m.put("p", price);
        m.put("rt", Long.toString(receivedAt));
        MapRecord<String, Object, Object> r = StreamRecords.newRecord().in(key).ofMap(m);
        when(stream.reverseRange(eq(key), any(Range.class), any(Limit.class))).thenReturn(List.of(r));
    }

    private BigDecimal futures(boolean buy, String qty) {
        return pricer.futures("BTCUSDT", buy, new BigDecimal(qty), FALLBACK);
    }

    @Test
    void 买单从卖一往上吃_取加权均价() {
        futuresBook(now, now);

        // 1×100.0 + 1×100.5 = 200.5 / 2
        assertThat(futures(true, "2")).isEqualByComparingTo("100.25");
    }

    @Test
    void 卖单从买一往下吃() {
        futuresBook(now, now);

        // 1×99.5 + 0.5×99.0 = 149.0 / 1.5
        assertThat(futures(false, "1.5")).isEqualByComparingTo("99.33333333");
    }

    @Test
    void 超过二十档的部分按最后一档的价() {
        futuresBook(now, now);

        // 卖盘一共 3：1×100.0 + 2×100.5 + 剩下 1×100.5 = 301.5 / 4
        assertThat(futures(true, "4")).isEqualByComparingTo("100.375");
    }

    @Test
    void 快照超过一秒或读不到或扫出错_用兜底价() {
        futuresBook(now - 1_500, now - 1_500);
        assertThat(futures(true, "1")).isEqualByComparingTo(FALLBACK);

        when(depth.snapshot("BTCUSDT")).thenReturn(null);
        assertThat(futures(true, "1")).isEqualByComparingTo(FALLBACK);

        when(depth.snapshot("BTCUSDT")).thenReturn(new Snapshot(now, "{坏的"));
        assertThat(futures(true, "1")).isEqualByComparingTo(FALLBACK);
    }

    /** 限价单：只吃挂单价以内的档，够整笔才算挂上就能成交 */
    @Test
    void 限价单挂单价以内的量够整笔才算能当场成交() {
        futuresBook(now, now);

        // 买 2 个挂 100.5：1×100.0 + 1×100.5 均价 100.25；挂 100.2 以内只有 1 个
        assertThat(pricer.futuresWithinLimit("BTCUSDT", true, new BigDecimal("2"), new BigDecimal("100.5"), FALLBACK))
                .isEqualByComparingTo("100.25");
        assertThat(pricer.futuresWithinLimit("BTCUSDT", true, new BigDecimal("2"), new BigDecimal("100.2"), FALLBACK))
                .isNull();
    }

    @Test
    void 限价单没盘口_看兜底价够不够得着挂单价() {
        when(depth.snapshot("BTCUSDT")).thenReturn(null);

        // 兜底价 99.8：买单挂 100 够得着，按 99.8 成交；挂 99 够不着
        assertThat(pricer.futuresWithinLimit("BTCUSDT", true, BigDecimal.ONE, new BigDecimal("100"), FALLBACK))
                .isEqualByComparingTo(FALLBACK);
        assertThat(pricer.futuresWithinLimit("BTCUSDT", true, BigDecimal.ONE, new BigDecimal("99"), FALLBACK))
                .isNull();
    }

    /** 补漏、积压的成交触发出来的单：触发价是旧价，跟现在的盘口对不上 */
    @Test
    void 触发价超过一秒_不扫盘口直接按触发价() {
        futuresBook(now, now);

        assertThat(pricer.futures("BTCUSDT", true, new BigDecimal("2"), FALLBACK, now - 1_500))
                .isEqualByComparingTo(FALLBACK);
        assertThat(pricer.futures("BTCUSDT", true, new BigDecimal("2"), FALLBACK, now))
                .isEqualByComparingTo("100.25");
    }

    @Test
    void 合约快照E早于最新成交T_买入不低于那笔成交_卖出不高于() {
        futuresBook(now, now - 100);
        latestTrade("market:orderflow:BTCUSDT", "100.8", now - 50, now - 200);
        assertThat(futures(true, "1")).isEqualByComparingTo("100.8");

        latestTrade("market:orderflow:BTCUSDT", "99.2", now - 50, now - 200);
        assertThat(futures(false, "1")).isEqualByComparingTo("99.2");
    }

    @Test
    void 合约快照E不早于最新成交T_照盘口不封顶() {
        // 收到时刻上快照反而更早，但合约只看 E 和 T
        futuresBook(now - 300, now - 50);
        latestTrade("market:orderflow:BTCUSDT", "100.8", now - 100, now);

        assertThat(futures(true, "1")).isEqualByComparingTo("100.0");
    }

    @Test
    void 现货没有E_比两边feed收到的时刻() {
        when(depth.spotSnapshot("BTCUSDT")).thenReturn(new Snapshot(now - 100, "{" + LEVELS + "}"));
        latestTrade("market:spot-trade:BTCUSDT", "100.8", now - 300, now - 50);
        assertThat(pricer.spot("BTCUSDT", true, BigDecimal.ONE, FALLBACK)).isEqualByComparingTo("100.8");

        latestTrade("market:spot-trade:BTCUSDT", "100.8", now - 30, now - 150);
        assertThat(pricer.spot("BTCUSDT", true, BigDecimal.ONE, FALLBACK)).isEqualByComparingTo("100.0");
    }
}
