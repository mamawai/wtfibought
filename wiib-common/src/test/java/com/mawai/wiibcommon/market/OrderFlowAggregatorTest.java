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

    /** 窗口里第一笔到最后一笔的价差；窗口外的不算，不到两笔回 null */
    @Test
    @SuppressWarnings("unchecked")
    void priceChangeFromFirstToLastTradeInWindow() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        OrderFlowAggregator agg = new OrderFlowAggregator(redisTemplate);

        long now = System.currentTimeMillis();
        MapRecord<String, Object, Object> stale = mapRecord("BTCUSDT", now - 20_000, 86_000, 1, "0");
        MapRecord<String, Object, Object> first = mapRecord("BTCUSDT", now - 8_000, 86_010, 1, "0");
        MapRecord<String, Object, Object> last = mapRecord("BTCUSDT", now, 86_025, 1, "1");
        when(streamOps.range(eq("market:orderflow:BTCUSDT"), any(Range.class))).thenReturn(List.of(stale, first, last));
        assertThat(agg.priceChange("BTCUSDT", 10)).isEqualTo(15.0);

        when(streamOps.range(eq("market:orderflow:BTCUSDT"), any(Range.class))).thenReturn(List.of(stale, last));
        assertThat(agg.priceChange("BTCUSDT", 10)).isNull();
    }

    /** 一次读、按边界分段：起点之前的不算，边界上的归后一段，末段不封顶；没成交的段是全 0，读不到回 null */
    @Test
    @SuppressWarnings("unchecked")
    void metricsBetweenSplitsByBounds() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        OrderFlowAggregator agg = new OrderFlowAggregator(redisTemplate);

        long t0 = 1_000_000;
        long[] bounds = {t0, t0 + 30_000, t0 + 60_000, t0 + 90_000};
        when(streamOps.range(eq("market:orderflow:BTCUSDT"), any(Range.class))).thenReturn(List.of(
                mapRecord("BTCUSDT", t0 - 1, 100, 9, "1"),              // 起点之前
                mapRecord("BTCUSDT", t0, 100, 3, "0"),                  // 第 1 段：买 300
                mapRecord("BTCUSDT", t0 + 10_000, 100, 1, "1"),         // 第 1 段：卖 100
                mapRecord("BTCUSDT", t0 + 60_000, 100, 2, "1"),         // 正好在边界上，归第 3 段
                mapRecord("BTCUSDT", t0 + 95_000, 100, 2, "0")));       // 晚于末个边界，也归第 3 段

        List<OrderFlowAggregator.Metrics> m = agg.getMetricsBetween("BTCUSDT", bounds);

        assertThat(m).hasSize(3);
        assertThat(m.get(0).tradeCount()).isEqualTo(2);
        assertThat(m.get(0).totalVolumeUsdt()).isEqualTo(400.0);
        assertThat(m.get(0).tradeDelta()).isCloseTo(0.5, within(1e-9));
        assertThat(m.get(1)).isEqualTo(new OrderFlowAggregator.Metrics(0, 0, 0, 0, 0));
        assertThat(m.get(2).tradeCount()).isEqualTo(2);
        assertThat(m.get(2).tradeDelta()).isCloseTo(0.0, within(1e-9));
        // 只读一次，服务端从首个边界往前 5 秒切
        ArgumentCaptor<Range<String>> captor = ArgumentCaptor.forClass(Range.class);
        verify(streamOps).range(eq("market:orderflow:BTCUSDT"), captor.capture());
        assertThat(captor.getValue().getLowerBound().getValue().orElseThrow()).isEqualTo((t0 - 5_000) + "-0");

        when(streamOps.range(eq("market:orderflow:BTCUSDT"), any(Range.class))).thenReturn(List.of());
        assertThat(agg.getMetricsBetween("BTCUSDT", bounds)).isNull();
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
