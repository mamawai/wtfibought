package com.mawai.wiibservice.controller;

import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyBacktestControllerTest {

    private static final long M5 = 5 * 60_000L;

    @Test
    void usesLatestLocalCloseTimeAsBacktestEnd() {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        long latestClose = 100 * M5 - 1;
        store.latestCloseTime = latestClose;
        store.bars = flatBars(80, latestClose - 80 * M5 + 1);

        StrategyBacktestController controller = new StrategyBacktestController(store);
        Result<Map<String, Object>> result = controller.runFibo(
                "BTCUSDT", 1, new BigDecimal("100000"), 5);

        assertThat(result.getData()).isNotNull();
        assertThat(store.loadToMs).isEqualTo(latestClose);
        assertThat(result.getData().get("latestCloseTime")).isEqualTo(latestClose);
        assertThat(result.getData().get("bars")).isEqualTo(80);
    }

    @Test
    void failsWhenLocalKlineHasNoLatestCloseTime() {
        StrategyBacktestController controller = new StrategyBacktestController(new FakeKlineHistoryStore());

        Result<Map<String, Object>> result = controller.runFibo(
                "BTCUSDT", 1, new BigDecimal("100000"), 5);

        assertThat(result.getData()).isNull();
        assertThat(result.getMsg()).contains("没有最新 5m K线");
    }

    @Test
    void failsWhenLoadedLocalKlineHasFiveMinuteGap() {
        FakeKlineHistoryStore store = new FakeKlineHistoryStore();
        store.latestCloseTime = 10 * M5 - 1;
        store.bars = new ArrayList<>(flatBars(10, 0));
        store.bars.remove(5);

        StrategyBacktestController controller = new StrategyBacktestController(store);
        Result<Map<String, Object>> result = controller.runFibo(
                "BTCUSDT", 1, new BigDecimal("100000"), 5);

        assertThat(result.getData()).isNull();
        assertThat(result.getMsg()).contains("5m K线不连续");
    }

    private static List<KlineBar> flatBars(int count, long startOpenTime) {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long openTime = startOpenTime + i * M5;
            BigDecimal price = BigDecimal.valueOf(100 + i % 3);
            bars.add(new KlineBar(openTime, openTime + M5 - 1,
                    price, price.add(BigDecimal.ONE), price.subtract(BigDecimal.ONE), price, BigDecimal.ONE));
        }
        return bars;
    }

    private static final class FakeKlineHistoryStore extends KlineHistoryStore {
        private Long latestCloseTime;
        private long loadToMs;
        private List<KlineBar> bars = List.of();

        private FakeKlineHistoryStore() {
            super(null);
        }

        @Override
        public Long latestCloseTime(String symbol, String intervalCode) {
            return latestCloseTime;
        }

        @Override
        public List<KlineBar> load(String symbol, String intervalCode, long fromMs, long toMs) {
            this.loadToMs = toMs;
            return bars;
        }
    }
}
