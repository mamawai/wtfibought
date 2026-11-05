package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.trader.trade.WatchesChanged;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盯盘哨兵：触及 / 到时刻看 tick，命中广播 WatchTrigger；周期收盘由调度器来问 closeHits，只认自己周期的边界。
 * 命中是库里 CAS 赢了才算，赢了就从名单摘掉
 */
class WatchSentinelTest {

    /** 2026-07-27 17:00:00 UTC 整点前 1ms：Binance closeTime 口径 */
    private static final long H1_CLOSE = 1785171599999L;

    private final TraderWatchStore store = mock(TraderWatchStore.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final WatchSentinel sentinel = new WatchSentinel(mock(RedisMessageListenerContainer.class), store, events);

    private static AiTraderWatch watch(long id, String kind, String level, String period, Long at) {
        AiTraderWatch w = new AiTraderWatch();
        w.setId(id);
        w.setTraderId(7L);
        w.setRoundNo(1);
        w.setSymbol("BTCUSDT");
        w.setKind(kind);
        w.setLevel(level == null ? null : new BigDecimal(level));
        w.setPeriod(period);
        w.setAtTime(at);
        w.setNote("看一眼");
        return w;
    }

    private static KlineBar bar(long closeTime, String close) {
        BigDecimal c = new BigDecimal(close);
        return new KlineBar(closeTime - 300_000L + 1, closeTime, c, c, c, c, BigDecimal.ONE);
    }

    private void armed(AiTraderWatch... watches) {
        when(store.allArmed()).thenReturn(List.of(watches));
        when(store.fire(anyLong(), anyLong(), any())).thenReturn(true);
        sentinel.reload();
    }

    @Test
    void touchFiresOnceAndPublishes() {
        armed(watch(1L, AiTraderWatch.TOUCH_ABOVE, "63000", null, null));

        sentinel.onPriceTick("BTCUSDT", new BigDecimal("62990"), 1_786_200_000_000L);
        verify(events, never()).publishEvent(any(Object.class));

        sentinel.onPriceTick("BTCUSDT", new BigDecimal("63000"), 1_786_200_001_000L);
        verify(events).publishEvent(argThat((Object o) -> o instanceof WatchTrigger w
                && w.watch().getId() == 1L && w.price().compareTo(new BigDecimal("63000")) == 0
                && w.triggeredAt() == 1_786_200_001_000L));

        // 已摘掉：价格继续走不再报，也不再碰库
        sentinel.onPriceTick("BTCUSDT", new BigDecimal("63500"), 1_786_200_002_000L);
        verify(events, times(1)).publishEvent(any(Object.class));
        verify(store, times(1)).fire(anyLong(), anyLong(), any());
    }

    /** 库里 CAS 没赢（刚被替换 / 并发 tick 别人先到）：不报 */
    @Test
    void casLostMeansNoPublish() {
        armed(watch(1L, AiTraderWatch.TOUCH_BELOW, "62000", null, null));
        when(store.fire(anyLong(), anyLong(), any())).thenReturn(false);

        sentinel.onPriceTick("BTCUSDT", new BigDecimal("61900"), 1_786_200_000_000L);

        verify(events, never()).publishEvent(any(Object.class));
    }

    /** 收盘类只认自己周期的边界：一刻钟上的 5m 收盘不算 1h 收盘；1h 边界上收盘价没跌破也不算；命中一次就摘掉 */
    @Test
    void closeHitsOnlyOnItsPeriodBoundary() {
        armed(watch(2L, AiTraderWatch.CLOSE_BELOW, "62000", "1h", null));

        assertThat(sentinel.closeHits("BTCUSDT", H1_CLOSE - 15 * 60_000L, bar(H1_CLOSE - 15 * 60_000L, "61900"))).isEmpty();
        assertThat(sentinel.closeHits("BTCUSDT", H1_CLOSE, bar(H1_CLOSE, "62100"))).isEmpty();

        long nextH1 = H1_CLOSE + 3_600_000L;
        List<WatchTrigger> hits = sentinel.closeHits("BTCUSDT", nextH1, bar(nextH1, "61900"));
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().watch().getId()).isEqualTo(2L);
        assertThat(hits.getFirst().price()).isEqualByComparingTo("61900");
        assertThat(hits.getFirst().triggeredAt()).isEqualTo(nextH1 + 1);

        assertThat(sentinel.closeHits("BTCUSDT", nextH1 + 3_600_000L, bar(nextH1 + 3_600_000L, "61800"))).isEmpty();
        verify(store, times(1)).fire(anyLong(), anyLong(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void atTimeFiresOnFirstTickPastTheMoment() {
        armed(watch(3L, AiTraderWatch.AT_TIME, null, null, 1_786_200_000_000L));

        sentinel.onPriceTick("BTCUSDT", new BigDecimal("63000"), 1_786_199_999_000L);
        verify(events, never()).publishEvent(any(Object.class));

        sentinel.onPriceTick("BTCUSDT", new BigDecimal("63000"), 1_786_200_000_000L);
        verify(events).publishEvent(argThat((Object o) -> o instanceof WatchTrigger w
                && w.watch().getId() == 3L && w.price().compareTo(new BigDecimal("63000")) == 0));
    }

    /** 条件一变（set_watch / 重置）整份重载：新布的下一个 tick 就在盯 */
    @Test
    void reloadsOnChange() {
        armed();
        sentinel.onPriceTick("BTCUSDT", new BigDecimal("63000"), 1_786_200_000_000L);
        verify(events, never()).publishEvent(any(Object.class));

        when(store.allArmed()).thenReturn(List.of(watch(4L, AiTraderWatch.TOUCH_ABOVE, "63000", null, null)));
        sentinel.onChanged(new WatchesChanged(7L));
        sentinel.onPriceTick("BTCUSDT", new BigDecimal("63000"), 1_786_200_001_000L);

        verify(events).publishEvent(any(Object.class));
    }
}
