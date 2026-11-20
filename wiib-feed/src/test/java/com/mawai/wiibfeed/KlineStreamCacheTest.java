package com.mawai.wiibfeed;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KlineStreamCacheTest {

    @Test
    @SuppressWarnings("unchecked")
    void persistsThenWritesStream() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        KlineHistoryStore historyStore = mock(KlineHistoryStore.class);
        KlineStreamCache cache = new KlineStreamCache(historyStore, redisTemplate);
        KlineBar bar = new KlineBar(1L, 2L, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.valueOf(5), BigDecimal.TEN);

        cache.onClosedBar("btcusdt", "5m", bar);

        // 落库和 XADD 在另起的虚拟线程里，等 XADD 做完再看
        ArgumentCaptor<MapRecord<String, String, String>> record = ArgumentCaptor.forClass(MapRecord.class);
        ArgumentCaptor<XAddOptions> options = ArgumentCaptor.forClass(XAddOptions.class);
        verify(streamOps, timeout(1000)).add(record.capture(), options.capture());
        assertThat(record.getValue().getStream()).isEqualTo(MarketStreamChannels.KLINE_CLOSED_STREAM);
        assertThat(record.getValue().getValue()).isEqualTo(Map.of(
                "symbol", "BTCUSDT", "interval", "5m", "openTime", "1", "closeTime", "2",
                "open", "1", "high", "10", "low", "0", "close", "5", "volume", "10"));
        assertThat(options.getValue().getMaxlen()).isEqualTo(10_000L);
        assertThat(options.getValue().isApproximateTrimming()).isTrue();

        // 先落库再发事件
        InOrder order = inOrder(historyStore, streamOps);
        order.verify(historyStore).saveClosedBar("BTCUSDT", "5m", bar);
        order.verify(streamOps).add(any(MapRecord.class), any(XAddOptions.class));
    }
}
