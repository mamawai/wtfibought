package com.mawai.wiibcommon.market;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderFlowAggregatorTest {

    @Test
    @SuppressWarnings("unchecked")
    void computesMetricsFromWindowRecords() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        OrderFlowAggregator agg = new OrderFlowAggregator(redisTemplate);

        long now = System.currentTimeMillis();
        // 1 笔买 100usdt(bm=0) + 1 笔卖 200usdt(bm=1) → total=300, tradeDelta=(100-200)/300=-1/3
        MapRecord<String, Object, Object> buy = mapRecord("BTCUSDT", now, 100, 1, "0");
        MapRecord<String, Object, Object> sell = mapRecord("BTCUSDT", now, 100, 2, "1");
        when(streamOps.range(eq("market:orderflow:BTCUSDT"), any(Range.class)))
                .thenReturn(List.of(buy, sell));

        OrderFlowAggregator.Metrics m = agg.getMetrics("BTCUSDT", 180);
        assertThat(m).isNotNull();
        assertThat(m.totalVolumeUsdt()).isEqualTo(300.0);
        assertThat(m.tradeCount()).isEqualTo(2);
        assertThat(m.tradeDelta()).isCloseTo(-1.0 / 3, within(1e-9));
    }

    @Test
    @SuppressWarnings("unchecked")
    void filtersOutRecordsOlderThanWindow() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        OrderFlowAggregator agg = new OrderFlowAggregator(redisTemplate);

        long now = System.currentTimeMillis();
        // 一笔在窗口内、一笔早于 60s 窗口 → 只算窗口内那笔
        MapRecord<String, Object, Object> fresh = mapRecord("BTCUSDT", now, 100, 1, "0");
        MapRecord<String, Object, Object> stale = mapRecord("BTCUSDT", now - 120_000, 100, 5, "1");
        when(streamOps.range(eq("market:orderflow:BTCUSDT"), any(Range.class)))
                .thenReturn(List.of(stale, fresh));

        OrderFlowAggregator.Metrics m = agg.getMetrics("BTCUSDT", 60);
        assertThat(m).isNotNull();
        assertThat(m.tradeCount()).isEqualTo(1);
        assertThat(m.totalVolumeUsdt()).isEqualTo(100.0);
    }

    /** 窗口裁剪必须发生在 Redis 服务端：起点是 cutoff 往前 5s，终点开放，否则又变成全量拉取 */
    @Test
    @SuppressWarnings("unchecked")
    void queriesOnlyWindowRangeOnServerSide() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.range(eq("market:orderflow:BTCUSDT"), any(Range.class))).thenReturn(List.of());
        OrderFlowAggregator agg = new OrderFlowAggregator(redisTemplate);

        long before = System.currentTimeMillis();
        agg.getMetrics("BTCUSDT", 180);
        long after = System.currentTimeMillis();

        ArgumentCaptor<Range<String>> captor = ArgumentCaptor.forClass(Range.class);
        verify(streamOps).range(eq("market:orderflow:BTCUSDT"), captor.capture());
        Range<String> range = captor.getValue();

        // 终点必须开放（等价 XRANGE 的 +），封死就取不到最新成交
        assertThat(range.getUpperBound().isBounded()).isFalse();

        // 起点 = (now - 180s - 5s)-0
        String lower = range.getLowerBound().getValue().orElseThrow();
        assertThat(lower).endsWith("-0");
        long lowerMs = Long.parseLong(lower.substring(0, lower.indexOf('-')));
        assertThat(lowerMs).isBetween(before - 185_000, after - 185_000);
    }

    private static MapRecord<String, Object, Object> mapRecord(String symbol, long ts, double p, double q, String bm) {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("ts", Long.toString(ts));
        m.put("p", Double.toString(p));
        m.put("q", Double.toString(q));
        m.put("bm", bm);
        return StreamRecords.newRecord().in("market:orderflow:" + symbol).ofMap(m);
    }
}
