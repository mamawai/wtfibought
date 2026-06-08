package com.mawai.wiibservice.service;

import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KlineStreamCacheTest {

    @Test
    void storesLatestClosedBarAndPublishesEvent() {
        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        FakeKlineHistoryStore historyStore = new FakeKlineHistoryStore();
        KlineStreamCache cache = new KlineStreamCache(publisher, historyStore);
        KlineBar bar = new KlineBar(1L, 2L, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.valueOf(5), BigDecimal.TEN);

        cache.onClosedBar("btcusdt", "5m", bar);

        assertThat(cache.latestClosed("BTCUSDT", "5m")).contains(bar);
        assertThat(cache.lastClosedMs("BTCUSDT", "5m")).isEqualTo(2L);
        assertThat(historyStore.savedSymbol).isEqualTo("BTCUSDT");
        assertThat(historyStore.savedInterval).isEqualTo("5m");
        assertThat(historyStore.savedBar).isEqualTo(bar);
        assertThat(events).singleElement().isInstanceOf(KlineClosedEvent.class);
    }

    private static final class FakeKlineHistoryStore extends KlineHistoryStore {
        private String savedSymbol;
        private String savedInterval;
        private KlineBar savedBar;

        private FakeKlineHistoryStore() {
            super(null);
        }

        @Override
        public void saveClosedBar(String symbol, String intervalCode, KlineBar bar) {
            savedSymbol = symbol;
            savedInterval = intervalCode;
            savedBar = bar;
        }
    }
}
