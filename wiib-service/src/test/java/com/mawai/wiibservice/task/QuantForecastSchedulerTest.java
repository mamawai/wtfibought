package com.mawai.wiibservice.task;

import com.mawai.wiibservice.agent.quant.PriceVolatilitySentinel;
import com.mawai.wiibservice.agent.quant.QuantForecastFacade;
import com.mawai.wiibservice.agent.quant.QuantLightCycleService;
import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantForecastSchedulerTest {

    @Test
    void deduplicatesLightRefreshTriggersWithinWindow() {
        BinanceRestClient binanceRestClient = mock(BinanceRestClient.class);
        QuantLightCycleService lightCycleService = mock(QuantLightCycleService.class);
        when(binanceRestClient.getFearGreedIndex(2)).thenReturn("{}");
        when(lightCycleService.hasCacheFor("BTCUSDT")).thenReturn(true);

        QuantForecastScheduler scheduler = new QuantForecastScheduler(
                mock(QuantForecastFacade.class),
                mock(RedisMessageBroadcastService.class),
                binanceRestClient,
                lightCycleService,
                mock(PriceVolatilitySentinel.class),
                mock(ApplicationEventPublisher.class));

        long closeTime = System.currentTimeMillis();
        scheduler.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", closeTime));
        scheduler.onKlineClosed(new KlineClosedEvent(this, "btcusdt", "5m", closeTime + 1));

        verify(lightCycleService, after(500).times(1)).runLightRefresh(eq("BTCUSDT"), anyString());
    }
}
