package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchPriceConsumerTest {

    private final CryptoOrderService cryptoOrderService = mock(CryptoOrderService.class);
    private final FuturesLiquidationService liquidationService = mock(FuturesLiquidationService.class);
    private final FuturesSettlementService settlementService = mock(FuturesSettlementService.class);
    private final CrossLiquidationService crossLiquidationService = mock(CrossLiquidationService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    @BeforeEach
    void stubRedis() {
        // dispatch 收尾会写 last-tick 键，KV 得有
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private MatchPriceConsumer consumer() {
        return new MatchPriceConsumer(
                mock(RedisMessageListenerContainer.class), redisTemplate, cryptoOrderService,
                liquidationService, settlementService, crossLiquidationService,
                mock(BinanceRestClient.class), mock(BinanceProperties.class));
    }

    private static Message msg(String json) {
        Message m = mock(Message.class);
        when(m.getBody()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return m;
    }

    @Test
    void dispatchesSpotToOnPriceUpdate() {
        consumer().onMessage(msg("{\"symbol\":\"BTCUSDT\",\"type\":\"spot\",\"price\":\"50000\"}"), null);
        // 撮合在虚拟线程异步执行，用 timeout 等待
        verify(cryptoOrderService, timeout(1000)).onPriceUpdate(eq("BTCUSDT"), eq(new BigDecimal("50000")));
    }

    @Test
    void dispatchesFuturesToSettlement() {
        consumer().onMessage(msg("{\"symbol\":\"BTCUSDT\",\"type\":\"futures\",\"price\":\"50000\"}"), null);
        verify(settlementService, timeout(1000)).onPriceUpdate(eq("BTCUSDT"), eq(new BigDecimal("50000")));
    }

    @Test
    void dispatchesMarkPriceToLiquidationWithCurrentPriceFromKv() {
        when(valueOps.get("market:futures-price:BTCUSDT")).thenReturn("49950");

        consumer().onMessage(msg("{\"symbol\":\"BTCUSDT\",\"type\":\"markprice\",\"price\":\"50000\"}"), null);
        // 强平：markPrice 来自事件，currentPrice 从 KV 读
        verify(liquidationService, timeout(1000))
                .checkOnPriceUpdate(eq("BTCUSDT"), eq(new BigDecimal("50000")), eq(new BigDecimal("49950")));
    }

}
