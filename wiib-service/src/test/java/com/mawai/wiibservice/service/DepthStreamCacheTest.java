package com.mawai.wiibservice.service;
import com.mawai.wiibcommon.market.DepthStreamCache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepthStreamCacheTest {

    @Test
    @SuppressWarnings("unchecked")
    void writesToRedisAndReadsFreshByEventTime() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        DepthStreamCache cache = new DepthStreamCache(redisTemplate);

        long now = System.currentTimeMillis();
        cache.onDepthUpdate("BTCUSDT", "{\"bids\":[[1,2]]}", now);
        // 写入：key 带前缀、value 为 "eventTime|json"、带 TTL
        verify(valueOps).set(eq("market:depth:BTCUSDT"), eq(now + "|{\"bids\":[[1,2]]}"), any(Duration.class));

        // 新鲜：eventTime 在窗口内 → 返回 json
        when(valueOps.get("market:depth:BTCUSDT")).thenReturn(now + "|{\"bids\":[[1,2]]}");
        assertThat(cache.getFreshDepth("BTCUSDT", 2000)).isEqualTo("{\"bids\":[[1,2]]}");
        assertThat(cache.hasFreshData("BTCUSDT")).isTrue();

        // 超龄：eventTime 早于窗口 → null
        when(valueOps.get("market:depth:BTCUSDT")).thenReturn((now - 5000) + "|{\"bids\":[[1,2]]}");
        assertThat(cache.getFreshDepth("BTCUSDT", 2000)).isNull();

        // 无数据 → null
        when(valueOps.get("market:depth:ETHUSDT")).thenReturn(null);
        assertThat(cache.getFreshDepth("ETHUSDT", 2000)).isNull();
    }
}
