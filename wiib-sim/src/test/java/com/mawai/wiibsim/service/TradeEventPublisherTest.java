package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.TradeEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.math.BigDecimal;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 持仓事件发布：载荷是 TradeEvent 的 JSON，在事务里要等提交后再发 */
class TradeEventPublisherTest {

    private static TradeEvent event() {
        return new TradeEvent(TradeEvent.STOP_LOSS, 7L, 9L, null, "BTCUSDT", "LONG",
                new BigDecimal("0.2"), new BigDecimal("94000"), new BigDecimal("-100"), 1_786_200_000_000L);
    }

    /** 不在事务里就立刻发；agent 侧按同一个记录读回来 */
    @Test
    void publishesJsonImmediatelyOutsideTransaction() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        new TradeEventPublisher(redis).publishAfterCommit(event());

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(redis).convertAndSend(eq(TradeEvent.CHANNEL), body.capture());
        TradeEvent decoded = MAPPER.readValue((String) body.getValue(), TradeEvent.class);
        assertThat(decoded.type()).isEqualTo(TradeEvent.STOP_LOSS);
        assertThat(decoded.userId()).isEqualTo(7L);
        assertThat(decoded.positionId()).isEqualTo(9L);
        assertThat(decoded.orderId()).isNull();
        assertThat(decoded.quantity()).isEqualByComparingTo("0.2");
        assertThat(decoded.at()).isEqualTo(1_786_200_000_000L);
    }

    /** 事务里：登记到提交后发，提交前一条都不发——先发出去 agent 醒来对账，库里还是旧状态 */
    @Test
    void waitsForCommitInsideTransaction() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        TransactionSynchronizationManager.initSynchronization();
        try {
            new TradeEventPublisher(redis).publishAfterCommit(event());
            verify(redis, never()).convertAndSend(any(), any());

            TransactionSynchronizationUtils.triggerAfterCommit();
            verify(redis).convertAndSend(eq(TradeEvent.CHANNEL), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
