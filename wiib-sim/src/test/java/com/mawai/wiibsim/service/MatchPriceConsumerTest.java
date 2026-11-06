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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 标记价驱动逐仓强平和全仓精查；最新价 tick 是给 quant 的，sim 不处理（限价单、止损止盈看成交 Stream） */
class MatchPriceConsumerTest {

    private final FuturesLiquidationService liquidationService = mock(FuturesLiquidationService.class);
    private final CrossLiquidationService crossLiquidationService = mock(CrossLiquidationService.class);
    private final TradeStreamConsumer tradeStreamConsumer = mock(TradeStreamConsumer.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    @BeforeEach
    void stubRedis() {
        // dispatch 收尾会写 last-tick 键，KV 得有
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private MatchPriceConsumer consumer() {
        return new MatchPriceConsumer(mock(RedisMessageListenerContainer.class), redisTemplate,
                liquidationService, crossLiquidationService, tradeStreamConsumer,
                mock(BinanceRestClient.class), mock(BinanceProperties.class));
    }

    private static Message msg(String json) {
        Message m = mock(Message.class);
        when(m.getBody()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return m;
    }

    @Test
    void 标记价_逐仓强平和全仓精查() {
        consumer().onMessage(msg("{\"symbol\":\"BTCUSDT\",\"type\":\"markprice\",\"price\":\"50000\"}"), null);

        // 撮合在虚拟线程异步执行，用 timeout 等待
        verify(liquidationService, timeout(1000)).checkOnPriceUpdate(eq("BTCUSDT"), eq(new BigDecimal("50000")));
        verify(crossLiquidationService, timeout(1000)).onPriceTick(eq("BTCUSDT"), eq(new BigDecimal("50000")));
    }

    @Test
    void 最新价tick_sim不处理() {
        consumer().onMessage(msg("{\"symbol\":\"BTCUSDT\",\"type\":\"futures\",\"price\":\"50000\"}"), null);

        verify(liquidationService, after(300).never()).checkOnPriceUpdate(any(), any());
        verifyNoInteractions(crossLiquidationService, tradeStreamConsumer);
    }
}
