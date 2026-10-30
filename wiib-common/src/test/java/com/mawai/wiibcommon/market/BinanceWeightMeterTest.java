package com.mawai.wiibcommon.market;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceWeightMeterTest {

    private static final long T0 = 1_700_000_000_000L;

    private final BinanceWeightMeter meter = new BinanceWeightMeter();

    @Test
    void 读数带时刻_60秒没新回包按0_没头或不是整数不更新() {
        meter.nowMs = () -> T0;
        meter.record("1300");
        assertThat(meter.reading()).isEqualTo(new BinanceWeightMeter.Reading(1300, T0, 2400));

        meter.record(null);
        meter.record("abc");
        assertThat(meter.reading().used()).isEqualTo(1300);

        meter.nowMs = () -> T0 + 59_000L;
        assertThat(meter.reading().used()).isEqualTo(1300);
        meter.nowMs = () -> T0 + 60_000L;
        assertThat(meter.reading().used()).isZero();
    }

    @Test
    void 过半打warn_一分钟只打一条() {
        Logger logger = (Logger) LoggerFactory.getLogger(BinanceWeightMeter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            meter.nowMs = () -> T0;
            meter.record("1199");
            meter.record("1200");
            meter.record("1300");
            meter.nowMs = () -> T0 + 60_000L;
            meter.record("1300");

            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.getFirst().getFormattedMessage()).contains("1200/2400").contains("50%");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void 拦截器只把合约站回包的头读进读数_现货站的跳过() throws IOException {
        ClientHttpRequestInterceptor interceptor = BinanceRestClient.weightInterceptor(meter, "fapi.binance.com");
        MockClientHttpResponse futures = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        futures.getHeaders().set(BinanceWeightMeter.HEADER, "77");
        MockClientHttpResponse spot = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        spot.getHeaders().set(BinanceWeightMeter.HEADER, "4000");

        interceptor.intercept(new MockClientHttpRequest(HttpMethod.GET, URI.create("https://fapi.binance.com/fapi/v1/time")),
                new byte[0], (request, body) -> futures);
        interceptor.intercept(new MockClientHttpRequest(HttpMethod.GET, URI.create("https://api.binance.com/api/v3/time")),
                new byte[0], (request, body) -> spot);

        assertThat(meter.reading().used()).isEqualTo(77);
    }
}
