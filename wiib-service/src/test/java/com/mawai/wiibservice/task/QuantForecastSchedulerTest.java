package com.mawai.wiibservice.task;

import com.mawai.wiibservice.agent.quant.PriceVolatilitySentinel;
import com.mawai.wiibservice.agent.quant.QuantForecastFacade;
import com.mawai.wiibservice.agent.quant.QuantForecastRunResult;
import com.mawai.wiibservice.agent.quant.domain.ForecastResult;
import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.quant.domain.QuantForecastRequestEvent;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class QuantForecastSchedulerTest {

    @Test
    void deduplicatesSameKlineCloseTimeWhileRunning() throws Exception {
        BinanceRestClient binanceRestClient = mock(BinanceRestClient.class);
        QuantForecastFacade facade = mock(QuantForecastFacade.class);
        CountDownLatch enteredRun = new CountDownLatch(1);
        CountDownLatch releaseRun = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredRun.countDown();
            releaseRun.await(2, TimeUnit.SECONDS);
            return QuantForecastRunResult.graphFailed();
        }).when(facade).run(anyString(), anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>any());

        QuantForecastScheduler scheduler = new QuantForecastScheduler(
                facade,
                mock(MarketBroadcaster.class),
                binanceRestClient,
                mock(PriceVolatilitySentinel.class),
                new FakeKlineHistoryStore());

        long closeTime = System.currentTimeMillis();
        scheduler.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", closeTime));
        org.assertj.core.api.Assertions.assertThat(enteredRun.await(1, TimeUnit.SECONDS)).isTrue();

        scheduler.onKlineClosed(new KlineClosedEvent(this, "btcusdt", "5m", closeTime));
        releaseRun.countDown();

        verify(facade, timeout(1000).times(1))
                .run(anyString(), anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>any());
    }

    @Test
    void sentinelRequestRunsForecastAndPreventsWatchdogDuplicate() throws Exception {
        BinanceRestClient binanceRestClient = mock(BinanceRestClient.class);
        QuantForecastFacade facade = mock(QuantForecastFacade.class);
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        long closeTime = System.currentTimeMillis();
        store.latestCloseTime = closeTime;
        store.latestOpenTime = closeTime - KlineHistoryStore.DEFAULT_BAR_MILLIS + 1;

        doAnswer(invocation -> QuantForecastRunResult.completed(null,
                new ForecastResult("BTCUSDT", "cycle-1", LocalDateTime.now(),
                        List.of(), "FLAT", "NORMAL", List.of(), null, null, null, null),
                null, null, null))
                .when(facade).run(anyString(), anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>any());

        QuantForecastScheduler scheduler = new QuantForecastScheduler(
                facade,
                mock(MarketBroadcaster.class),
                binanceRestClient,
                mock(PriceVolatilitySentinel.class),
                store);

        scheduler.onForecastRequest(new QuantForecastRequestEvent(this, "btcusdt", 0L, "sentinel", true));

        verify(facade, timeout(1000).times(1))
                .run(anyString(), anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>any());

        scheduler.watchdogFallback();

        verify(facade, timeout(300).times(1))
                .run(anyString(), anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>any());
    }

    private static final class FakeKlineHistoryStore extends KlineHistoryStore {
        private Long latestCloseTime;
        private Long latestOpenTime;

        private FakeKlineHistoryStore() {
            super(null);
        }

        @Override
        public Long latestCloseTime(String symbol, String intervalCode) {
            return "BTCUSDT".equals(symbol) ? latestCloseTime : null;
        }

        @Override
        public Long latestOpenTime(String symbol, String intervalCode) {
            return "BTCUSDT".equals(symbol) ? latestOpenTime : null;
        }

        @Override
        public int backfill(String symbol, long fromMs, long toMs) {
            return 0;
        }
    }
}
