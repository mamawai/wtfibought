package com.mawai.wiibquant.agent.quant.stream;

import com.mawai.wiibquant.agent.quant.PriceVolatilitySentinel;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SentinelPriceConsumerTest {

    private static Message msg(String json) {
        Message m = mock(Message.class);
        when(m.getBody()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return m;
    }

    @Test
    void feedsMarkPriceToSentinel() {
        PriceVolatilitySentinel sentinel = mock(PriceVolatilitySentinel.class);
        SentinelPriceConsumer consumer = new SentinelPriceConsumer(mock(RedisMessageListenerContainer.class), sentinel);
        consumer.onMessage(msg("{\"symbol\":\"BTCUSDT\",\"type\":\"markprice\",\"price\":\"50000\"}"), null);
        verify(sentinel).onPriceTick(eq("BTCUSDT"), eq(new BigDecimal("50000")));
    }

    @Test
    void ignoresNonMarkPriceTypes() {
        PriceVolatilitySentinel sentinel = mock(PriceVolatilitySentinel.class);
        SentinelPriceConsumer consumer = new SentinelPriceConsumer(mock(RedisMessageListenerContainer.class), sentinel);
        // 同通道也有现货撮合价，哨兵只取 markprice
        consumer.onMessage(msg("{\"symbol\":\"BTCUSDT\",\"type\":\"spot\",\"price\":\"50000\"}"), null);
        verifyNoInteractions(sentinel);
    }
}
