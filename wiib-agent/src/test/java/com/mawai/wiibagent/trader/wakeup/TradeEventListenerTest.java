package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.market.TradeEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 持仓事件监听：按 sim 用户认 trader，认到了广播一条触发，准入归调度器；消息体是 TradeEvent 的 JSON */
class TradeEventListenerTest {

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TradeEventListener listener =
            new TradeEventListener(mock(RedisMessageListenerContainer.class), traderMapper, events);

    private static TradeEvent event() {
        return new TradeEvent(TradeEvent.LIMIT_FILLED, 42L, 9L, 100L, "BTCUSDT", "LONG",
                new BigDecimal("0.01"), new BigDecimal("63120"), null, 1_786_200_000_000L);
    }

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        t.setSimUserId(42L);
        t.setStatus(AiTrader.STATUS_RUNNING);
        return t;
    }

    /** 事件里的 sim 用户是某个 RUNNING trader 的子账户 → 广播给这个 trader 一条 EventTrigger */
    @Test
    void wakesTraderOwningTheSimAccount() {
        AiTrader t = trader();
        when(traderMapper.selectList(any())).thenReturn(List.of(t));

        listener.onEvent(event());

        verify(events).publishEvent(new TriggerFired(7L, new EventTrigger(event())));
    }

    /** 普通用户的止损不是 trader 的事 */
    @Test
    void ignoresEventsOfUsersWithoutTrader() {
        when(traderMapper.selectList(any())).thenReturn(List.of());

        listener.onEvent(event());

        verify(events, never()).publishEvent(any(Object.class));
    }

    /** Redis 消息体原样读回成 TradeEvent；坏消息只丢本条 */
    @Test
    void decodesRedisMessageAndDropsGarbage() throws Exception {
        AiTrader t = trader();
        when(traderMapper.selectList(any())).thenReturn(List.of(t));
        byte[] channel = TradeEvent.CHANNEL.getBytes(StandardCharsets.UTF_8);

        listener.onMessage(new DefaultMessage(channel, "not json".getBytes(StandardCharsets.UTF_8)), null);
        listener.onMessage(new DefaultMessage(channel, MAPPER.writeValueAsBytes(event())), null);

        verify(events, timeout(2_000)).publishEvent(new TriggerFired(7L, new EventTrigger(event())));
        verify(events, after(300).times(1)).publishEvent(any(Object.class));
    }
}
