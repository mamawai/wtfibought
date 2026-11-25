package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.TradeEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.math.BigDecimal;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 持仓事件发布：写进 Stream，字段 json 是 TradeEvent 的 JSON，近似裁剪；在事务里要等提交后再写 */
class TradeEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    {
        when(redis.opsForStream()).thenReturn(stream);
    }

    private static TradeEvent event() {
        return new TradeEvent(TradeEvent.STOP_LOSS, 7L, 9L, null, "BTCUSDT", "LONG",
                new BigDecimal("0.2"), new BigDecimal("94000"), new BigDecimal("-100"), 1_786_200_000_000L);
    }

    /** 不在事务里就立刻写；agent 侧按同一个记录读回来 */
    @Test
    @SuppressWarnings("unchecked")
    void writesJsonImmediatelyOutsideTransaction() {
        new TradeEventPublisher(redis).publishAfterCommit(event());

        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<XAddOptions> options = ArgumentCaptor.forClass(XAddOptions.class);
        verify(stream).add(eq(TradeEvent.STREAM), fields.capture(), options.capture());
        TradeEvent decoded = MAPPER.readValue(fields.getValue().get("json"), TradeEvent.class);
        assertThat(decoded.type()).isEqualTo(TradeEvent.STOP_LOSS);
        assertThat(decoded.userId()).isEqualTo(7L);
        assertThat(decoded.positionId()).isEqualTo(9L);
        assertThat(decoded.orderId()).isNull();
        assertThat(decoded.quantity()).isEqualByComparingTo("0.2");
        assertThat(decoded.at()).isEqualTo(1_786_200_000_000L);
        assertThat(options.getValue().getMaxlen()).isEqualTo(10_000L);
        assertThat(options.getValue().isApproximateTrimming()).isTrue();
    }

    /** 事务里：登记到提交后写，提交前一条都不写——先写出去 agent 醒来对账，库里还是旧状态 */
    @Test
    void waitsForCommitInsideTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            new TradeEventPublisher(redis).publishAfterCommit(event());
            verify(stream, never()).add(anyString(), anyMap(), any(XAddOptions.class));

            TransactionSynchronizationUtils.triggerAfterCommit();
            verify(stream).add(eq(TradeEvent.STREAM), anyMap(), any(XAddOptions.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
