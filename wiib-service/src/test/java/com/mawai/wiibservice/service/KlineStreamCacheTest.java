package com.mawai.wiibservice.service;

import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KlineStreamCacheTest {

    @Test
    void storesLatestClosedBarAndPublishesEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        KlineHistoryStore historyStore = mock(KlineHistoryStore.class);
        KlineStreamCache cache = new KlineStreamCache(publisher, historyStore);
        KlineBar bar = new KlineBar(1L, 2L, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.valueOf(5), BigDecimal.TEN);

        cache.onClosedBar("btcusdt", "5m", bar);

        assertThat(cache.latestClosed("BTCUSDT", "5m")).contains(bar);
        assertThat(cache.lastClosedMs("BTCUSDT", "5m")).isEqualTo(2L);
        verify(historyStore).saveClosedBar("BTCUSDT", "5m", bar);
        verify(publisher).publishEvent(any(KlineClosedEvent.class));
    }
}
