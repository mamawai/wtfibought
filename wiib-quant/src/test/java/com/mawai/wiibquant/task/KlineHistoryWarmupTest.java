package com.mawai.wiibquant.task;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.quant.service.MacroContextService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KlineHistoryWarmupTest {

    @Test
    void backfillsAllConfiguredSymbolsOverHistoryWindowThenWarmsMacro() {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        FakeMacroContextService macro = new FakeMacroContextService(store);

        warmup(store, macro, List.of("BTCUSDT", "ETHUSDT", "SOLUSDT")).run();

        assertThat(store.symbols).containsExactly("BTCUSDT", "ETHUSDT", "SOLUSDT");
        assertThat(store.lastToMs - store.lastFromMs).isEqualTo(MacroContextService.HISTORY.toMillis());
        assertThat(macro.warmupCalls).isEqualTo(1);
        // 预热必须发生在全部回补之后，宏观才能读到完整窗口
        assertThat(macro.backfillsBeforeWarmup).isEqualTo(3);
    }

    @Test
    void singleSymbolFailureContinuesWithOthersAndStillWarmsMacro() {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        store.failSymbol = "BTCUSDT";
        FakeMacroContextService macro = new FakeMacroContextService(store);

        warmup(store, macro, List.of("BTCUSDT", "ETHUSDT")).run();

        assertThat(store.symbols).containsExactly("BTCUSDT", "ETHUSDT");
        assertThat(macro.warmupCalls).isEqualTo(1);
    }

    @Test
    void warmsMacroEvenWithoutConfiguredSymbols() {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        FakeMacroContextService macro = new FakeMacroContextService(store);

        warmup(store, macro, null).run();

        assertThat(store.symbols).isEmpty();
        assertThat(macro.warmupCalls).isEqualTo(1);
    }

    private static KlineHistoryWarmup warmup(FakeKlineHistoryStore store,
                                             FakeMacroContextService macro,
                                             List<String> symbols) {
        BinanceProperties props = new BinanceProperties();
        props.setSymbols(symbols);
        return new KlineHistoryWarmup(store, props, macro);
    }

    private static final class FakeKlineHistoryStore extends KlineHistoryStore {
        private final List<String> symbols = new ArrayList<>();
        private long lastFromMs;
        private long lastToMs;
        private String failSymbol;

        private FakeKlineHistoryStore() {
            super(null);
        }

        @Override
        public int backfillMissing(String symbol, long fromMs, long toMs) {
            symbols.add(symbol);
            lastFromMs = fromMs;
            lastToMs = toMs;
            if (symbol.equals(failSymbol)) {
                throw new RuntimeException("rest down");
            }
            return 1;
        }
    }

    private static final class FakeMacroContextService extends MacroContextService {
        private final FakeKlineHistoryStore store;
        private int warmupCalls;
        private int backfillsBeforeWarmup;

        private FakeMacroContextService(FakeKlineHistoryStore store) {
            super(null, null);
            this.store = store;
        }

        @Override
        public void warmup() {
            warmupCalls++;
            backfillsBeforeWarmup = store.symbols.size();
        }
    }
}
