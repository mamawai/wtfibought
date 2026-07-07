package com.mawai.wiibquant.agent.strategy.liq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 键格式/时效语义是 feed↔quant 跨模块契约，这里锁死读方解析行为。 */
class RedisLiqSideDataTest {

    private static final long STALE = 600_000L;   // 与 LiqFadeParams.defaults 同款 10min
    private static final long T = 1_700_000_000_000L;

    private ValueOperations<String, String> ops;
    private RedisLiqSideData data;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        data = new RedisLiqSideData(redis, STALE);
    }

    @Test
    void taker买量_按桶openMs直查_缺桶NaN() {
        when(ops.get("market:takerbuy5m:BTCUSDT:" + T)).thenReturn("123.45");
        assertThat(data.takerBuy("BTCUSDT", T)).isEqualTo(123.45);
        assertThat(data.takerBuy("BTCUSDT", T + 300_000)).isNaN();
    }

    @Test
    void premium_等于最新价对指数价的比例偏离() {
        when(ops.get("market:indexprice:BTCUSDT")).thenReturn("100000|" + T);
        when(ops.get("market:futures-price:BTCUSDT")).thenReturn("99850");
        // (99850-100000)/100000 = -15bp
        assertThat(data.premiumAt("BTCUSDT", T)).isEqualTo(-0.0015);
    }

    @Test
    void premium_指数价采样超时效NaN_恰好压线放行() {
        when(ops.get("market:futures-price:BTCUSDT")).thenReturn("99850");
        when(ops.get("market:indexprice:BTCUSDT")).thenReturn("100000|" + (T - STALE - 1));
        assertThat(data.premiumAt("BTCUSDT", T)).isNaN();

        when(ops.get("market:indexprice:BTCUSDT")).thenReturn("100000|" + (T - STALE));
        assertThat(data.premiumAt("BTCUSDT", T)).isEqualTo(-0.0015);
    }

    @Test
    void premium_采样比atMs新_实盘正常放行不算未来函数() {
        when(ops.get("market:indexprice:BTCUSDT")).thenReturn("100000|" + (T + 2_000));
        when(ops.get("market:futures-price:BTCUSDT")).thenReturn("99850");
        assertThat(data.premiumAt("BTCUSDT", T)).isEqualTo(-0.0015);
    }

    @Test
    void premium_任一键缺失NaN() {
        when(ops.get("market:indexprice:BTCUSDT")).thenReturn(null);
        when(ops.get("market:futures-price:BTCUSDT")).thenReturn("99850");
        assertThat(data.premiumAt("BTCUSDT", T)).isNaN();

        when(ops.get("market:indexprice:BTCUSDT")).thenReturn("100000|" + T);
        when(ops.get("market:futures-price:BTCUSDT")).thenReturn(null);
        assertThat(data.premiumAt("BTCUSDT", T)).isNaN();
    }
}
