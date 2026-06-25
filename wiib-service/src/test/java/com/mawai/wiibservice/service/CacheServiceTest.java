package com.mawai.wiibservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheServiceTest {

    @SuppressWarnings("unchecked")
    private static CacheService withRedis(ValueOperations<String, String> valueOps) {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        return new CacheService(redisTemplate, stringRedisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void predictionBidAskGoThroughRedis() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        CacheService cache = withRedis(valueOps);

        cache.putPredictionAsk("UP", new BigDecimal("0.55"));
        verify(valueOps).set(eq("prediction:UP:ask"), eq("0.55"), any(Duration.class));

        when(valueOps.get("prediction:UP:ask")).thenReturn("0.55");
        assertThat(cache.getPredictionAsk("UP")).isEqualByComparingTo("0.55");

        when(valueOps.get("prediction:DOWN:bid")).thenReturn(null);
        assertThat(cache.getPredictionBid("DOWN")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void polymarketOpenCloseGoThroughRedis() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        CacheService cache = withRedis(valueOps);

        cache.putPolymarketOpenPrice(1700000000L, new BigDecimal("95000"));
        verify(valueOps).set(eq("prediction:openprice:1700000000"), eq("95000"), any(Duration.class));

        when(valueOps.get("prediction:closeprice:1700000000")).thenReturn("95500");
        assertThat(cache.getPolymarketClosePrice(1700000000L)).isEqualByComparingTo("95500");
    }
}
