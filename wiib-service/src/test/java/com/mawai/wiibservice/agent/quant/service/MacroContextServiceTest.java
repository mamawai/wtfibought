package com.mawai.wiibservice.agent.quant.service;

import com.mawai.wiibservice.agent.quant.domain.MacroContext;
import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.VolatilityRiskTier;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MacroContextServiceTest {

    @Test
    void loadsOneMinuteHistoryAndAggregatesToOneHourMacroLegs() {
        KlineHistoryStore store = mock(KlineHistoryStore.class);
        long now = System.currentTimeMillis();
        when(store.latestOpenTime(eq("BTCUSDT"), eq("1m"))).thenReturn(now - 60_000L);
        when(store.load(eq("BTCUSDT"), eq("1m"), anyLong(), anyLong()))
                .thenReturn(oneMinuteBars(now, 31));

        MacroContextService service = new MacroContextService(store);
        service.refreshIfStale("BTCUSDT");

        MacroContext context = service.get("BTCUSDT");
        assertThat(context.stale()).isFalse();
        assertThat(context.legs()).containsKeys(ForecastHorizon.H6, ForecastHorizon.H12, ForecastHorizon.H24);
        assertThat(context.legs().values())
                .extracting(MacroContext.Leg::volTier)
                .doesNotContain(VolatilityRiskTier.UNKNOWN);
        verify(store).load(eq("BTCUSDT"), eq("1m"), anyLong(), anyLong());
    }

    @Test
    void backfillsRecentTailWhenDbIsStaleButNotCold() {
        KlineHistoryStore store = mock(KlineHistoryStore.class);
        long now = System.currentTimeMillis();
        when(store.latestOpenTime(eq("BTCUSDT"), eq("1m"))).thenReturn(now - 30 * 60_000L);
        when(store.load(eq("BTCUSDT"), eq("1m"), anyLong(), anyLong()))
                .thenReturn(oneMinuteBars(now, 31));

        MacroContextService service = new MacroContextService(store);
        service.refreshIfStale("BTCUSDT");

        verify(store).backfill(eq("BTCUSDT"), anyLong(), anyLong());
    }

    @Test
    void klineCloseEventSkipsRefreshWhenMacroContextIsFresh() throws InterruptedException {
        KlineHistoryStore store = mock(KlineHistoryStore.class);
        long now = System.currentTimeMillis();
        when(store.latestOpenTime(eq("BTCUSDT"), eq("1m"))).thenReturn(now - 60_000L);
        when(store.load(eq("BTCUSDT"), eq("1m"), anyLong(), anyLong()))
                .thenReturn(oneMinuteBars(now, 31));

        MacroContextService service = new MacroContextService(store);
        service.refreshIfStale("BTCUSDT");
        assertThat(service.get("BTCUSDT").stale()).isFalse();

        clearInvocations(store);
        service.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", now));
        Thread.sleep(100);

        verifyNoInteractions(store);
    }

    private static List<KlineBar> oneMinuteBars(long now, int days) {
        int count = days * 24 * 60;
        long start = now - count * 60_000L;
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> {
                    long openTime = start + i * 60_000L;
                    BigDecimal open = BigDecimal.valueOf(100.0 + i * 0.001);
                    BigDecimal close = open.add(BigDecimal.valueOf(Math.sin(i / 20.0) * 0.05));
                    return new KlineBar(openTime, openTime + 59_999L,
                            open, open.add(BigDecimal.valueOf(0.2)), open.subtract(BigDecimal.valueOf(0.2)),
                            close, BigDecimal.TEN);
                })
                .toList();
    }
}
