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
                factory, marketDataService, mock(PriceVolatilitySentinel.class), new FakeKlineHistoryStore(),
                3600_000L, 900_000L);

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
                factory, marketDataService, mock(PriceVolatilitySentinel.class), store,
                3600_000L, 900_000L);

        scheduler.onForecastRequest(new QuantForecastRequestEvent(this, "btcusdt", 0L, "sentinel", true));

        verify(graph, timeout(1000).times(1))
                .invoke(org.mockito.ArgumentMatchers.<Map<String, Object>>any());

        scheduler.watchdogFallback();

        verify(graph, timeout(300).times(1))
                .invoke(org.mockito.ArgumentMatchers.<Map<String, Object>>any());
    }

    @Test
    void firstBarTriggersDeepAndSecondWithinIntervalDoesNot() throws Exception {
        QuantSnapshotGraphFactory factory = mock(QuantSnapshotGraphFactory.class);
        CompiledGraph graph = mock(CompiledGraph.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        when(factory.get()).thenReturn(graph);
        when(marketDataService.assemble(any())).thenReturn(MarketAssembly.unavailable("BTCUSDT", Map.of()));

        // 图返回 snapshot_id + analysis_id → 深研判记账生效
        com.alibaba.cloud.ai.graph.OverAllState state = mock(com.alibaba.cloud.ai.graph.OverAllState.class);
        when(state.value("snapshot_id")).thenReturn(Optional.of(1L));
        when(state.value("analysis_id")).thenReturn(Optional.of(2L));
        when(graph.invoke(org.mockito.ArgumentMatchers.<Map<String, Object>>any())).thenReturn(Optional.of(state));

        QuantSnapshotScheduler scheduler = new QuantSnapshotScheduler(
                factory, marketDataService, mock(PriceVolatilitySentinel.class), new FakeKlineHistoryStore(),
                3600_000L, 900_000L);

        long closeTime = System.currentTimeMillis();
        scheduler.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", closeTime));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(graph, timeout(1000).times(1)).invoke(captor.capture());
        // 冷启动 lastDeep=0 → 首根 bar 即触发深研判（定频窗口已满）
        org.assertj.core.api.Assertions.assertThat(captor.getValue().get("trigger_deep")).isEqualTo(true);

        // 深研判已记账 → 下一根 bar（间隔<1h）不再触发深研判
        scheduler.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", closeTime + 300_000));
        verify(graph, timeout(1000).times(2)).invoke(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().get("trigger_deep")).isEqualTo(false);
    }

    @Test
    void sentinelJumpRespectsCooldown() throws Exception {
        QuantSnapshotGraphFactory factory = mock(QuantSnapshotGraphFactory.class);
        CompiledGraph graph = mock(CompiledGraph.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        when(factory.get()).thenReturn(graph);
        when(marketDataService.assemble(any())).thenReturn(MarketAssembly.unavailable("BTCUSDT", Map.of()));
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        long closeTime = System.currentTimeMillis();
        store.latestCloseTime = closeTime;

        com.alibaba.cloud.ai.graph.OverAllState state = mock(com.alibaba.cloud.ai.graph.OverAllState.class);
        when(state.value("snapshot_id")).thenReturn(Optional.of(1L));
        when(state.value("analysis_id")).thenReturn(Optional.of(2L));
        when(graph.invoke(org.mockito.ArgumentMatchers.<Map<String, Object>>any())).thenReturn(Optional.of(state));

        QuantSnapshotScheduler scheduler = new QuantSnapshotScheduler(
                factory, marketDataService, mock(PriceVolatilitySentinel.class), store,
                3600_000L, 900_000L);

        // 第一次哨兵插队：冷启动可深研判
        scheduler.onForecastRequest(new QuantForecastRequestEvent(this, "BTCUSDT", closeTime, "sentinel", true));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(graph, timeout(1000).times(1)).invoke(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().get("trigger_deep")).isEqualTo(true);

        // 冷却期内第二次插队：force 仍产快照，但深研判被冷却压制
        scheduler.onForecastRequest(new QuantForecastRequestEvent(this, "BTCUSDT", closeTime + 1, "sentinel", true));
        verify(graph, timeout(1000).times(2)).invoke(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().get("trigger_deep")).isEqualTo(false);
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
