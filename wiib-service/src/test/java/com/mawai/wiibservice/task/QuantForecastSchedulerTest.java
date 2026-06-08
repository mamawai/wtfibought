package com.mawai.wiibservice.task;

import com.mawai.wiibservice.agent.quant.PriceVolatilitySentinel;
import com.mawai.wiibservice.agent.quant.QuantForecastFacade;
import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class QuantForecastSchedulerTest {

    @Test
    void deduplicatesSameKlineCloseTimeWhileRunning() throws Exception {
        BinanceRestClient binanceRestClient = mock(BinanceRestClient.class);
        QuantForecastFacade facade = mock(QuantForecastFacade.class);
        CountDownLatch enteredRun = new CountDownLatch(1);
        CountDownLatch releaseRun = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredRun.countDown();
            releaseRun.await(2, TimeUnit.SECONDS);
            return new com.mawai.wiibservice.agent.quant.QuantForecastRunResult(
                    false, false, null, null, null, null, null);
        }).when(facade).run(anyString(), anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>any());

        QuantForecastScheduler scheduler = new QuantForecastScheduler(
                facade,
                mock(RedisMessageBroadcastService.class),
                binanceRestClient,
                mock(PriceVolatilitySentinel.class),
                mock(ApplicationEventPublisher.class),
                new FakeKlineHistoryStore());

        long closeTime = System.currentTimeMillis();
        scheduler.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", closeTime));
        org.assertj.core.api.Assertions.assertThat(enteredRun.await(1, TimeUnit.SECONDS)).isTrue();

        scheduler.onKlineClosed(new KlineClosedEvent(this, "btcusdt", "5m", closeTime));
        releaseRun.countDown();

        verify(facade, timeout(1000).times(1))
                .run(anyString(), anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>any());
    }

    private static final class FakeKlineHistoryStore extends KlineHistoryStore {
        private FakeKlineHistoryStore() {
            super(null);
        }
    }
}
