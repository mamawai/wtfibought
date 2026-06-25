package com.mawai.wiibservice.service;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KlineStreamCacheTest {

    @Test
    @SuppressWarnings("unchecked")
    void persistsAndWritesStream() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        FakeKlineHistoryStore historyStore = new FakeKlineHistoryStore();
        KlineStreamCache cache = new KlineStreamCache(historyStore, redisTemplate);
        KlineBar bar = new KlineBar(1L, 2L, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.valueOf(5), BigDecimal.TEN);

        cache.onClosedBar("btcusdt", "5m", bar);

        assertThat(historyStore.saved.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(historyStore.savedSymbol).isEqualTo("BTCUSDT");
        assertThat(historyStore.savedInterval).isEqualTo("5m");
        assertThat(historyStore.savedBar).isEqualTo(bar);
        // onClosedBar 不发 Spring 事件，改写 Redis Stream（事件由 KlineStreamConsumer republish）
        verify(streamOps).add(any(MapRecord.class));
        verify(streamOps).trim(eq(KlineStreamCache.CLOSED_STREAM_KEY), anyLong(), anyBoolean());
    }

    private static final class FakeKlineHistoryStore extends KlineHistoryStore {
        private final CountDownLatch saved = new CountDownLatch(1);
        private String savedSymbol;
        private String savedInterval;
        private KlineBar savedBar;

        private FakeKlineHistoryStore() {
            super(null);
        }

        @Override
        public void saveClosedBar(String symbol, String intervalCode, KlineBar bar) {
            savedSymbol = symbol;
            savedInterval = intervalCode;
            savedBar = bar;
            saved.countDown();
        }
    }
}
