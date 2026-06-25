package com.mawai.wiibservice.service;
import com.mawai.wiibcommon.cache.CacheService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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

    @Test
    @SuppressWarnings("unchecked")
    void officialWindowRoundTripsThroughRedisJson() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        CacheService cache = withRedis(valueOps);

        cache.putPredictionOfficialWindow(1700000000L, 100L, 200L, 150L, 160L);
        // 捕获真实写入的 JSON，再喂回去回读——验证 fastjson2 对 record 的序列化/反序列化闭环
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("prediction:window:1700000000"), json.capture(), any(Duration.class));

        when(valueOps.get("prediction:window:1700000000")).thenReturn(json.getValue());
        CacheService.PredictionOfficialWindow w = cache.getPredictionOfficialWindow(1700000000L);
        assertThat(w).isNotNull();
        assertThat(w.windowStart()).isEqualTo(1700000000L);
        assertThat(w.startTimeMs()).isEqualTo(100L);
        assertThat(w.endTimeMs()).isEqualTo(200L);
        assertThat(w.referenceNowMs()).isEqualTo(150L);
        assertThat(w.referenceLocalTimeMs()).isEqualTo(160L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void btcPriceHistoryGoesThroughZSet() {
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zset);
        CacheService cache = new CacheService(mock(RedisTemplate.class), stringRedisTemplate);

        cache.addBtcPricePoint(1700000000000L, new BigDecimal("95000.5"));
        verify(zset).add("prediction:btcprice:history", "1700000000000:95000.5", 1700000000000.0);
        // 裁掉 6 分钟外的旧点
        verify(zset).removeRangeByScore("prediction:btcprice:history", 0, 1699999640000.0);

        ZSetOperations.TypedTuple<String> tuple =
                new DefaultTypedTuple<>("1700000000000:95000.5", 1700000000000.0);
        when(zset.rangeByScoreWithScores("prediction:btcprice:history", 1699999000000.0, Double.MAX_VALUE))
                .thenReturn(new LinkedHashSet<>(List.of(tuple)));
        List<Map<String, Object>> history = cache.getBtcPriceHistory(1699999000000L);
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsEntry("time", 1700000000000L).containsEntry("price", "95000.5");
    }
}
