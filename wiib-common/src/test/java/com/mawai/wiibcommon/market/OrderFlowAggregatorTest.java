package com.mawai.wiibcommon.market;

import org.junit.jupiter.api.Test;
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
    void writesAggTradeToStream() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        OrderFlowAggregator agg = new OrderFlowAggregator(redisTemplate);

        agg.onAggTrade("BTCUSDT", 100.0, 1.0, false, System.currentTimeMillis());
        verify(streamOps).add(any(MapRecord.class));
    }

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

    private static MapRecord<String, Object, Object> mapRecord(String symbol, long ts, double p, double q, String bm) {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("ts", Long.toString(ts));
        m.put("p", Double.toString(p));
        m.put("q", Double.toString(q));
        m.put("bm", bm);
        return StreamRecords.newRecord().in("market:orderflow:" + symbol).ofMap(m);
    }
}
