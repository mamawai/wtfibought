package com.mawai.wiibquant.agent.quant.service;

import com.mawai.wiibquant.agent.quant.domain.MacroContext;
import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibquant.agent.research.forecast.VolatilityRiskTier;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mawai.wiibquant.agent.quant.service.ResearchFeatureAssembler.AssemblyResult;
import static org.assertj.core.api.Assertions.assertThat;

class MacroContextServiceTest {

    @Test
    void loadsFiveMinuteHistoryAsMacroLegFeatures() {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        ResearchFeatureAssembler assembler = stubAssembler();
        long now = System.currentTimeMillis();
        store.latestOpenTime = now - 5 * 60_000L;
        store.loadBars = fiveMinuteBars(now, 31);

        MacroContextService service = new MacroContextService(store, assembler);

        MacroContext context = service.computeNow("BTCUSDT", now);
        assertThat(context.stale()).isFalse();
        assertThat(context.legs()).containsKeys(ForecastHorizon.H6, ForecastHorizon.H12, ForecastHorizon.H24);
        assertThat(context.legs().values())
                .extracting(MacroContext.Leg::volTier)
                .doesNotContain(VolatilityRiskTier.UNKNOWN);
        assertThat(store.loadCalls).isEqualTo(1);
        assertThat(store.lastLoadSymbol).isEqualTo("BTCUSDT");
        assertThat(store.lastLoadInterval).isEqualTo("5m");
    }

    @Test
    void backfillsRecentTailWhenDbIsStaleButNotCold() {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        ResearchFeatureAssembler assembler = stubAssembler();
        long now = System.currentTimeMillis();
        store.latestOpenTime = now - 30 * 60_000L;
        store.loadBars = fiveMinuteBars(now, 31);

        MacroContextService service = new MacroContextService(store, assembler);
        service.computeNow("BTCUSDT", now);

        assertThat(store.backfillCalls).isEqualTo(1);
    }

    @Test
    void klineCloseEventSkipsRefreshWhenMacroContextIsFresh() throws InterruptedException {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        ResearchFeatureAssembler assembler = stubAssembler();
        long now = System.currentTimeMillis();
        store.latestOpenTime = now - 5 * 60_000L;
        store.loadBars = fiveMinuteBars(now, 31);

        MacroContextService service = new MacroContextService(store, assembler);
        assertThat(service.computeNow("BTCUSDT", now).stale()).isFalse();

        store.clearCalls();
        service.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", now));
        Thread.sleep(100);

        assertThat(store.totalCalls()).isZero();
    }

    @Test
    void lightRefreshSchedulesLongBackfillWhenHistoryIsCold() throws InterruptedException {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        store.backfillEntered = new CountDownLatch(1);
        ResearchFeatureAssembler assembler = stubAssembler();
        long now = System.currentTimeMillis();

        MacroContextService service = new MacroContextService(store, assembler);
        service.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", now));

        assertThat(store.backfillEntered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(store.backfillCalls).isEqualTo(1);
    }

    @Test
    void computeNowBypassesBackgroundRefreshAndKeepsNewestCache() throws Exception {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        long now = System.currentTimeMillis();
        long syncCloseTime = now + 20 * 60_000L;
        store.latestOpenTime = syncCloseTime;
        store.firstLoadEntered = new CountDownLatch(1);
        store.releaseFirstLoad = new CountDownLatch(1);
        store.firstLoadBars = fiveMinuteBars(now, 31);
        store.secondLoadBars = fiveMinuteBars(syncCloseTime, 31);

        AtomicInteger assemblyCalls = new AtomicInteger();
        ResearchFeatureAssembler assembler = new ResearchFeatureAssembler(null) {
            @Override
            public AssemblyResult assemble(String symbol, List<KlineBar> bars5m) {
                String flag = assemblyCalls.getAndIncrement() == 0 ? "SYNC_NEW" : "BACKGROUND_OLD";
                return new AssemblyResult(ResearchFeatures.ofBars(bars5m), List.of(flag));
            }
        };

        MacroContextService service = new MacroContextService(store, assembler);

        service.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", now));
        assertThat(store.firstLoadEntered.await(1, TimeUnit.SECONDS)).isTrue();

        MacroContext sync = service.computeNow("BTCUSDT", syncCloseTime);
        assertThat(sync.qualityFlags()).contains("SYNC_NEW");

        store.releaseFirstLoad.countDown();
        Thread.sleep(200);

        MacroContext cached = cachedContext(service, "BTCUSDT");
        assertThat(cached.qualityFlags()).contains("SYNC_NEW");
        assertThat(cached.qualityFlags()).doesNotContain("BACKGROUND_OLD");
    }

    @SuppressWarnings("unchecked")
    private static MacroContext cachedContext(MacroContextService service, String symbol) throws Exception {
        Field field = MacroContextService.class.getDeclaredField("cache");
        field.setAccessible(true);
        Map<String, MacroContext> cache = (Map<String, MacroContext>) field.get(service);
        return cache.get(symbol);
    }

    /** 返回中性外生因子的 assembler（测试不关心外生因子具体值，只验证 research core 能跑通） */
    private static ResearchFeatureAssembler stubAssembler() {
        return new ResearchFeatureAssembler(null) {
            @Override
            public AssemblyResult assemble(String symbol, List<KlineBar> bars5m) {
                return new AssemblyResult(ResearchFeatures.ofBars(bars5m), List.of());
            }
        };
    }

    private static List<KlineBar> fiveMinuteBars(long now, int days) {
        int count = days * 24 * 12;
        long start = now - count * 5 * 60_000L;
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> {
                    long openTime = start + i * 5 * 60_000L;
                    BigDecimal open = BigDecimal.valueOf(100.0 + i * 0.001);
                    BigDecimal close = open.add(BigDecimal.valueOf(Math.sin(i / 20.0) * 0.05));
                    return new KlineBar(openTime, openTime + 5 * 60_000L - 1,
                            open, open.add(BigDecimal.valueOf(0.2)), open.subtract(BigDecimal.valueOf(0.2)),
                            close, BigDecimal.TEN);
                })
                .toList();
    }

    private static final class FakeKlineHistoryStore extends KlineHistoryStore {
        private Long latestOpenTime;
        private List<KlineBar> loadBars = List.of();
        private List<KlineBar> firstLoadBars;
        private List<KlineBar> secondLoadBars;
        private CountDownLatch firstLoadEntered;
        private CountDownLatch releaseFirstLoad;
        private CountDownLatch backfillEntered;
        private int loadCalls;
        private int backfillCalls;
        private int latestOpenTimeCalls;
        private String lastLoadSymbol;
        private String lastLoadInterval;

        private FakeKlineHistoryStore() {
            super(null);
        }

        @Override
        public List<KlineBar> load(String symbol, String intervalCode, long fromMs, long toMs) {
            loadCalls++;
            lastLoadSymbol = symbol;
            lastLoadInterval = intervalCode;
            if (loadCalls == 1 && firstLoadEntered != null && releaseFirstLoad != null) {
                firstLoadEntered.countDown();
                try {
                    releaseFirstLoad.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (loadCalls == 1 && firstLoadBars != null) {
                return firstLoadBars;
            }
            if (loadCalls == 2 && secondLoadBars != null) {
                return secondLoadBars;
            }
            return loadBars;
        }

        @Override
        public Long latestOpenTime(String symbol, String intervalCode) {
            latestOpenTimeCalls++;
            return latestOpenTime;
        }

        @Override
        public int backfill(String symbol, long fromMs, long toMs) {
            backfillCalls++;
            if (backfillEntered != null) {
                backfillEntered.countDown();
            }
            return 1;
        }

        private void clearCalls() {
            loadCalls = 0;
            backfillCalls = 0;
            latestOpenTimeCalls = 0;
        }

        private int totalCalls() {
            return loadCalls + backfillCalls + latestOpenTimeCalls;
        }
    }
}
