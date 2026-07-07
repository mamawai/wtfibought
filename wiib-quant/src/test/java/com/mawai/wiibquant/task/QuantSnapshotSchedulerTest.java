package com.mawai.wiibquant.task;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.quant.PriceVolatilitySentinel;
import com.mawai.wiibquant.agent.quant.QuantSnapshotGraphFactory;
import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibquant.agent.quant.domain.QuantForecastRequestEvent;
import com.mawai.wiibquant.agent.toolkit.MarketAssembly;
import com.mawai.wiibquant.agent.toolkit.MarketDataService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantSnapshotSchedulerTest {

    @Test
    void deduplicatesSameKlineCloseTimeWhileRunning() throws Exception {
        QuantSnapshotGraphFactory factory = mock(QuantSnapshotGraphFactory.class);
        CompiledGraph graph = mock(CompiledGraph.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        when(factory.get()).thenReturn(graph);
        when(marketDataService.assemble(any())).thenReturn(MarketAssembly.unavailable("BTCUSDT", Map.of()));
        CountDownLatch enteredRun = new CountDownLatch(1);
        CountDownLatch releaseRun = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredRun.countDown();
            releaseRun.await(2, TimeUnit.SECONDS);
            return Optional.empty();
        }).when(graph).invoke(org.mockito.ArgumentMatchers.<Map<String, Object>>any());

        QuantSnapshotScheduler scheduler = new QuantSnapshotScheduler(
                factory, marketDataService, mock(PriceVolatilitySentinel.class), new FakeKlineHistoryStore());

        long closeTime = System.currentTimeMillis();
        scheduler.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", closeTime));
        org.assertj.core.api.Assertions.assertThat(enteredRun.await(1, TimeUnit.SECONDS)).isTrue();

        // 同 closeTime 二次触发：runningKey 去重，不再 invoke
        scheduler.onKlineClosed(new KlineClosedEvent(this, "btcusdt", "5m", closeTime));
        releaseRun.countDown();

        verify(graph, timeout(1000).times(1))
                .invoke(org.mockito.ArgumentMatchers.<Map<String, Object>>any());
    }

    @Test
    void sentinelRequestRunsSnapshotAndPreventsWatchdogDuplicate() throws Exception {
        QuantSnapshotGraphFactory factory = mock(QuantSnapshotGraphFactory.class);
        CompiledGraph graph = mock(CompiledGraph.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        when(factory.get()).thenReturn(graph);
        when(marketDataService.assemble(any())).thenReturn(MarketAssembly.unavailable("BTCUSDT", Map.of()));
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        long closeTime = System.currentTimeMillis();
        store.latestCloseTime = closeTime;
        store.latestOpenTime = closeTime - KlineHistoryStore.DEFAULT_BAR_MILLIS + 1;

        // 图产出 snapshot_id=1 → lastSnapshotCloseTime 记账 → watchdog 不重复触发
        com.alibaba.cloud.ai.graph.OverAllState state = mock(com.alibaba.cloud.ai.graph.OverAllState.class);
        when(state.value("snapshot_id")).thenReturn(Optional.of(1L));
        when(graph.invoke(org.mockito.ArgumentMatchers.<Map<String, Object>>any())).thenReturn(Optional.of(state));

        QuantSnapshotScheduler scheduler = new QuantSnapshotScheduler(
                factory, marketDataService, mock(PriceVolatilitySentinel.class), store);

        scheduler.onForecastRequest(new QuantForecastRequestEvent(this, "btcusdt", 0L, "sentinel", true));

        verify(graph, timeout(1000).times(1))
                .invoke(org.mockito.ArgumentMatchers.<Map<String, Object>>any());

        scheduler.watchdogFallback();

        verify(graph, timeout(300).times(1))
                .invoke(org.mockito.ArgumentMatchers.<Map<String, Object>>any());
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
