package com.mawai.wiibservice.agent.quant.service;

import com.mawai.wiibservice.agent.quant.domain.MacroContext;
import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibservice.agent.research.forecast.VolatilityRiskTier;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.mawai.wiibservice.agent.quant.service.ResearchFeatureAssembler.AssemblyResult;
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
        service.refreshIfStale("BTCUSDT");

        MacroContext context = service.get("BTCUSDT");
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
        service.refreshIfStale("BTCUSDT");

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
        service.refreshIfStale("BTCUSDT");
        assertThat(service.get("BTCUSDT").stale()).isFalse();

        store.clearCalls();
        service.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", now));
        Thread.sleep(100);

        assertThat(store.totalCalls()).isZero();
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
