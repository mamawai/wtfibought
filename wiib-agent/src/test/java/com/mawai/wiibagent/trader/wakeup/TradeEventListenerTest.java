package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.TradeEvent;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
    private final SimTradeClient sim = mock(SimTradeClient.class);
    private final UnreportedEvents unreported = mock(UnreportedEvents.class);
    private final UserLangResolver langResolver = mock(UserLangResolver.class);
    private final TradeEventListener listener = new TradeEventListener(mock(RedisMessageListenerContainer.class),
            traderMapper, events, sim, unreported, langResolver, new PromptCatalog());

    {
        when(langResolver.of(anyLong())).thenReturn(AgentLang.ZH);
    }

    private static TradeEvent event() {
        return new TradeEvent(TradeEvent.LIMIT_FILLED, 42L, 9L, 100L, "BTCUSDT", "LONG",
                new BigDecimal("0.01"), new BigDecimal("63120"), null, 1_786_200_000_000L);
    }

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(3L);
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

    private static TradeEvent takeProfit() {
        return new TradeEvent(TradeEvent.TAKE_PROFIT, 42L, 9L, null, "BTCUSDT", "LONG",
                new BigDecimal("0.01"), new BigDecimal("64000"), new BigDecimal("8.5"), 1_786_200_000_000L);
    }

    /** 止盈触发不叫醒；平掉一部分、仓位还在的记一行，归那个币，下一轮开场白报 */
    @Test
    void partialTakeProfitDoesNotWakeButIsNotedForNextOpening() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader()));
        FuturesPositionDTO remaining = new FuturesPositionDTO();
        remaining.setId(9L);
        when(sim.getAllPositions(42L)).thenReturn(List.of(remaining));

        listener.onEvent(takeProfit());

        verify(events, never()).publishEvent(any(Object.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UnreportedEvents.Line>> lines = ArgumentCaptor.forClass(List.class);
        verify(unreported).add(eq(7L), lines.capture());
        assertThat(lines.getValue()).singleElement().satisfies(line -> {
            assertThat(line.symbol()).isEqualTo("BTCUSDT");
            assertThat(line.text()).contains("BTCUSDT LONG 止盈单在").contains("平掉 0.01 @64000，仓位还在");
        });
    }

    /** 整仓止盈平完：不叫醒也不另记，对账会报结局 */
    @Test
    void fullTakeProfitIsLeftToReconciliation() {
        when(traderMapper.selectList(any())).thenReturn(List.of(trader()));
        when(sim.getAllPositions(42L)).thenReturn(List.of());

        listener.onEvent(takeProfit());

        verify(events, never()).publishEvent(any(Object.class));
        verify(unreported, never()).add(anyLong(), any());
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
