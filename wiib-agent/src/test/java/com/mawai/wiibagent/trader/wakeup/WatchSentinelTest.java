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
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盯盘哨兵：触及 / 到时刻看 tick，命中广播 WatchTrigger；周期收盘由调度器来问 closeHits，只认自己周期的边界。
 * 命中是库里 CAS 赢了才算，赢了就从名单摘掉；带动作的先交给执行器，它说要叫醒才叫
 */
class WatchSentinelTest {

    /** 2026-07-27 17:00:00 UTC 整点前 1ms：Binance closeTime 口径 */
    private static final long H1_CLOSE = 1785171599999L;

    private final TraderWatchStore store = mock(TraderWatchStore.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PresetExecutor presets = mock(PresetExecutor.class);
    private final WatchSentinel sentinel = new WatchSentinel(mock(RedisMessageListenerContainer.class), store, events, presets);

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

    /** 库里 CAS 没赢（刚被替换 / 并发 tick 别人先到）：不报，也从名单摘掉，下一个 tick 不再去碰库 */
    @Test
    void casLostMeansNoPublishAndEntryDropped() {
        armed(watch(1L, AiTraderWatch.TOUCH_BELOW, "62000", null, null));
        when(store.fire(anyLong(), anyLong(), any())).thenReturn(false);

        sentinel.onPriceTick("BTCUSDT", new BigDecimal("61900"), 1_786_200_000_000L);
        sentinel.onPriceTick("BTCUSDT", new BigDecimal("61800"), 1_786_200_001_000L);

        verify(events, never()).publishEvent(any(Object.class));
        verify(store, times(1)).fire(anyLong(), anyLong(), any());
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

    /** 带动作的条件命中：先交给执行器做掉；它说不用叫醒（移止损、撤单做成了）就不广播，说要叫醒才广播 */
    @Test
    void presetRunsFirstAndWakesOnlyWhenExecutorSaysSo() {
        AiTraderWatch quiet = watch(5L, AiTraderWatch.TOUCH_ABOVE, "63000", null, null);
        quiet.setActionJson("{\"type\":\"MOVE_STOP\"}");
        AiTraderWatch waking = watch(6L, AiTraderWatch.TOUCH_BELOW, "61000", null, null);
        waking.setActionJson("{\"type\":\"CLOSE\"}");
        armed(quiet, waking);
        when(presets.execute(waking, new BigDecimal("60990"), 1_786_200_001_000L)).thenReturn(true);

        sentinel.onPriceTick("BTCUSDT", new BigDecimal("63010"), 1_786_200_000_000L);
        sentinel.onPriceTick("BTCUSDT", new BigDecimal("60990"), 1_786_200_001_000L);

        verify(presets, timeout(2_000)).execute(quiet, new BigDecimal("63010"), 1_786_200_000_000L);
        verify(events, timeout(2_000)).publishEvent(argThat((Object o) -> o instanceof WatchTrigger w && w.watch().getId() == 6L));
        verify(events, after(300).times(1)).publishEvent(any(Object.class));
    }

    /** 收盘类的预案在 closeHits 里当场做完：不用叫醒的不进返回的名单 */
    @Test
    void closePresetExecutesInlineAndQuietOnesAreNotReturned() {
        AiTraderWatch quiet = watch(7L, AiTraderWatch.CLOSE_BELOW, "62000", "1h", null);
        quiet.setActionJson("{\"type\":\"CANCEL_ORDER\"}");
        AiTraderWatch plain = watch(8L, AiTraderWatch.CLOSE_BELOW, "62500", "1h", null);
        armed(quiet, plain);

        List<WatchTrigger> hits = sentinel.closeHits("BTCUSDT", H1_CLOSE, bar(H1_CLOSE, "61900"));

        verify(presets).execute(quiet, new BigDecimal("61900"), H1_CLOSE + 1);
        assertThat(hits).extracting(t -> t.watch().getId()).containsExactly(8L);
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
