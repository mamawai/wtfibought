package com.mawai.wiibsim.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PredictionRoundConsumerTest {

    private final PredictionService predictionService = mock(PredictionService.class);

    private PredictionRoundConsumer consumer() {
        return new PredictionRoundConsumer(
                mock(StringRedisTemplate.class), mock(RedisConnectionFactory.class), predictionService);
    }

    @SuppressWarnings("unchecked")
    private static MapRecord<String, String, String> record(Map<String, String> fields) {
        MapRecord<String, String, String> r = mock(MapRecord.class);
        when(r.getValue()).thenReturn(fields);
        return r;
    }

    @Test
    void lockUsesWindowStartFromEvent() {
        // lock 必须用事件里的旧窗口，不能用消费时刻的当前窗口
        consumer().onMessage(record(Map.of("type", "lock", "windowStart", "1700000000")));
        verify(predictionService).lockRound(1700000000L);
    }

    @Test
    void createTriggersCreateNewRound() {
        consumer().onMessage(record(Map.of("type", "create", "windowStart", "1700000300")));
        verify(predictionService).createNewRound();
    }

    @Test
    void syncopenTriggersSyncOpenPrice() {
        consumer().onMessage(record(Map.of("type", "syncopen", "windowStart", "1700000300")));
        verify(predictionService).syncOpenPrice();
    }

    @Test
    void settleTriggersSettlePreviousRound() {
        consumer().onMessage(record(Map.of("type", "settle", "windowStart", "1700000000")));
        verify(predictionService).settlePreviousRound();
    }

    @Test
    void unknownTypeIsIgnored() {
        consumer().onMessage(record(Map.of("type", "bogus", "windowStart", "1")));
        verifyNoInteractions(predictionService);
    }
}
