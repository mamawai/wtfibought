package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.market.TradeEvent;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 持仓事件监听：按 sim 用户认 trader，认到了交给调度器准入；消息体是 TradeEvent 的 JSON */
class TradeEventListenerTest {

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final TraderScheduler scheduler = mock(TraderScheduler.class);
    private final TradeEventListener listener =
            new TradeEventListener(mock(RedisMessageListenerContainer.class), traderMapper, scheduler);

    private static TradeEvent event() {
        return new TradeEvent(TradeEvent.LIMIT_FILLED, 42L, 9L, 100L, "BTCUSDT", "LONG",
                new BigDecimal("0.01"), new BigDecimal("63120"), null, 1_786_200_000_000L);
    }

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setSimUserId(42L);
        return t;
    }

    /** 事件里的 sim 用户是某个 RUNNING trader 的子账户 → 以 EventTrigger 交给调度器 */
    @Test
    void wakesTraderOwningTheSimAccount() {
        AiTrader t = trader();
        when(traderMapper.selectList(any())).thenReturn(List.of(t));

        listener.onEvent(event());

        verify(scheduler).tryTriggerWake(eq(7L),
                argThat((WakeTrigger tr) -> tr instanceof EventTrigger e && e.event().equals(event())));
    }

    /** 普通用户的止损不是 trader 的事 */
    @Test
    void ignoresEventsOfUsersWithoutTrader() {
        when(traderMapper.selectList(any())).thenReturn(List.of());

        listener.onEvent(event());

        verify(scheduler, never()).tryTriggerWake(anyLong(), any());
    }

    /** Redis 消息体原样读回成 TradeEvent；坏消息只丢本条 */
    @Test
    void decodesRedisMessageAndDropsGarbage() throws Exception {
        AiTrader t = trader();
        when(traderMapper.selectList(any())).thenReturn(List.of(t));
        byte[] channel = TradeEvent.CHANNEL.getBytes(StandardCharsets.UTF_8);

        listener.onMessage(new DefaultMessage(channel, "not json".getBytes(StandardCharsets.UTF_8)), null);
        listener.onMessage(new DefaultMessage(channel, MAPPER.writeValueAsBytes(event())), null);

        verify(scheduler, timeout(2_000)).tryTriggerWake(eq(7L), any(EventTrigger.class));
        verify(scheduler, after(300).times(1)).tryTriggerWake(anyLong(), any());
    }
}
