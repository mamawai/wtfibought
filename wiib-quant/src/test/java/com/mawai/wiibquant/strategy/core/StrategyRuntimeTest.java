package com.mawai.wiibquant.strategy.core;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.strategy.execution.StrategyExecutionPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StrategyRuntimeTest {

    private static final long M5 = 5 * 60_000L;
    private static final String SYM = "BTCUSDT";

    @Test
    void backfillsSkippedBarBeforeEvaluating() {
        FakeStore store = new FakeStore();
        store.bars = List.of(bar(1), bar(2));
        store.latestClose = bar(1).closeTime();   // seed 只装到第 1 根
        RecStrategy strategy = new RecStrategy();
        StrategyRuntime runtime = new StrategyRuntime(List.of(strategy), store, null, null,
                mock(StrategyExecutionPort.class));
        ReflectionTestUtils.setField(runtime, "enabledIds", "STUB");

        runtime.evaluate(SYM, bar(3));            // 第 2 根的收盘漏了

        assertThat(strategy.seenBars).containsExactly(bar(1), bar(2), bar(3));
        assertThat(strategy.seenGap).isFalse();
    }

    private static KlineBar bar(int i) {
        long open = i * M5;
        BigDecimal p = BigDecimal.valueOf(100);
        return new KlineBar(open, open + M5 - 1, p, p, p, p, BigDecimal.ONE);
    }

    /** 记下被评估时看到的 5m 窗口。 */
    private static final class RecStrategy implements TradingStrategySpi {
        List<KlineBar> seenBars;
        boolean seenGap;

        @Override
        public String id() {
            return "STUB";
        }

        @Override
        public List<String> symbols() {
            return List.of(SYM);
        }

        @Override
        public StrategyRiskPolicy riskPolicy() {
            return StrategyRiskPolicy.defaults();
        }

        @Override
        public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
            seenBars = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, Integer.MAX_VALUE);
            seenGap = view.hasBaseGap();
            return Optional.empty();
        }
    }

    /** 库：load 按 openTime 落在 [from, to) 取，回补不做事。 */
    private static final class FakeStore extends KlineHistoryStore {
        List<KlineBar> bars = List.of();
        Long latestClose;

        private FakeStore() {
            super(null);
        }

        @Override
        public int backfillMissing(String symbol, long fromMs, long toMs) {
            return 0;
        }

        @Override
        public Long latestCloseTime(String symbol, String intervalCode) {
            return latestClose;
        }

        @Override
        public List<KlineBar> load(String symbol, String intervalCode, long fromMs, long toMs) {
            return bars.stream().filter(b -> b.openTime() >= fromMs && b.openTime() < toMs).toList();
        }
    }
}
