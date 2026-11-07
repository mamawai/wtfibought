package com.mawai.wiibquant.task;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KlineHistoryWarmupTest {

    @Test
    void backfillsAllConfiguredSymbolsOverHistoryWindow() {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        BinanceProperties props = new BinanceProperties();
        props.setSymbols(List.of("BTCUSDT", "ETHUSDT", "SOLUSDT"));
        // 只有永续的加密币也要回补
        props.setCryptoFuturesSymbols(List.of("HYPEUSDT"));

        new KlineHistoryWarmup(store, props).run();

        assertThat(store.symbols).containsExactly("BTCUSDT", "ETHUSDT", "SOLUSDT", "HYPEUSDT");
        assertThat(store.lastToMs - store.lastFromMs).isEqualTo(Duration.ofDays(90).toMillis());
    }

    @Test
    void singleSymbolFailureContinuesWithOthers() {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        store.failSymbol = "BTCUSDT";

        warmup(store, List.of("BTCUSDT", "ETHUSDT")).run();

        assertThat(store.symbols).containsExactly("BTCUSDT", "ETHUSDT");
    }

    @Test
    void noConfiguredSymbolsIsNoop() {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();

        warmup(store, null).run();

        assertThat(store.symbols).isEmpty();
    }

    private static KlineHistoryWarmup warmup(FakeKlineHistoryStore store, List<String> symbols) {
        BinanceProperties props = new BinanceProperties();
        props.setSymbols(symbols);
        return new KlineHistoryWarmup(store, props);
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
}
