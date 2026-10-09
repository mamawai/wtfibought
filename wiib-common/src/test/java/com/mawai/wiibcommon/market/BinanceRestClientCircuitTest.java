package com.mawai.wiibcommon.market;

import com.mawai.wiibcommon.config.BinanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流熔断：Binance 的 429 是"慢点"，不理它会升级成 418 直接封 IP（最长 3 天），
 * 而封的是整个进程的出口 IP——策略执行轨跟对话轨共用同一个客户端，一起瘫。
 */
class BinanceRestClientCircuitTest {

    private static BinanceProperties props() {
        BinanceProperties p = new BinanceProperties();
        p.setRestBaseUrl("http://localhost:1");
        p.setFuturesRestBaseUrl("http://localhost:1");
        return p;
    }

    /** 撞到 429 之后，冷却期内不许再发请求——继续打只会把 429 催成 418 */
    @Test
    void 撞到429后冷却期内不再发请求() {
        AtomicInteger calls = new AtomicInteger();
        BinanceRestClient client = new BinanceRestClient(props()) {
            @Override
            protected String get(String uri) {
                calls.incrementAndGet();
                throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", new HttpHeaders(), null, null);
            }
        };

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();
        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();
        assertThat(client.getFutures24hTicker("BTCUSDT")).isNull();

        // 只有第一次真打出去，后两次被熔断直接短路
        assertThat(calls.get()).isEqualTo(1);
    }

    /** 冷却期过后要自动恢复——熔断是暂停不是永久关闭 */
    @Test
    void 冷却期过后恢复请求() {
        AtomicInteger calls = new AtomicInteger();
        BinanceRestClient client = new BinanceRestClient(props()) {
            @Override
            protected String get(String uri) {
                if (calls.incrementAndGet() == 1) {
                    throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                            "Too Many Requests", new HttpHeaders(), null, null);
                }
                return "[]";
            }
        };
        client.nowMs = fixedClock(0L);

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();
        client.nowMs = fixedClock(BinanceRestClient.COOLDOWN_MS + 1);

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isEqualTo("[]");
        assertThat(calls.get()).isEqualTo(2);
    }

    /** 回包带的 Retry-After 比 COOLDOWN_MS 长时按 Retry-After 冷却 */
    @Test
    void Retry_After更长时按它冷却() {
        AtomicInteger calls = new AtomicInteger();
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "300");
        BinanceRestClient client = new BinanceRestClient(props()) {
            @Override
            protected String get(String uri) {
                if (calls.incrementAndGet() == 1) {
                    throw HttpClientErrorException.create(HttpStatus.I_AM_A_TEAPOT,
                            "I'm a teapot", headers, null, null);
                }
                return "[]";
            }
        };
        client.nowMs = fixedClock(0L);

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();
        client.nowMs = fixedClock(BinanceRestClient.COOLDOWN_MS + 1);
        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();
        client.nowMs = fixedClock(300_001L);
        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isEqualTo("[]");

        assertThat(calls.get()).isEqualTo(2);
    }

    /** 普通 4xx（比如参数错）不该触发熔断，否则一个笔误就把全局行情停了 */
    @Test
    void 普通4xx不触发熔断() {
        AtomicInteger calls = new AtomicInteger();
        BinanceRestClient client = new BinanceRestClient(props()) {
            @Override
            protected String get(String uri) {
                calls.incrementAndGet();
                throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST,
                        "Bad Request", new HttpHeaders(), null, null);
            }
        };

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();
        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();

        assertThat(calls.get()).isEqualTo(2);
    }

    /** 418 才是真正的 IP ban（最短 2 分钟、最长 3 天），比 429 更该熔断，不能只覆盖 429 */
    @Test
    void 撞到418后冷却期内不再发请求() {
        AtomicInteger calls = new AtomicInteger();
        BinanceRestClient client = new BinanceRestClient(props()) {
            @Override
            protected String get(String uri) {
                calls.incrementAndGet();
                throw HttpClientErrorException.create(HttpStatus.I_AM_A_TEAPOT,
                        "I'm a teapot", new HttpHeaders(), null, null);
            }
        };

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();
        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();

        assertThat(calls.get()).isEqualTo(1);
    }

    /**
     * 已经 encode 过的 symbols 参数不能再被 RestTemplate 的 UriTemplateHandler 编码一遍：
     * %5B 变 %255B，Binance 收到字面量返回 400，而 400 被熔断层吞成 null，
     * getSpotExchangeInfo / get24hTickers 就永久静默失效（过滤器冻在硬编码快照、bStock 列表永久降级）。
     */
    @Test
    void 已编码的symbols参数不能被二次编码() {
        SENT_URI.set(null);
        BinanceRestClient client = new BinanceRestClient(props()) {
            @Override
            protected RestTemplate createRestTemplate(int connectTimeout, int readTimeout) {
                return recordingRestTemplate();
            }
        };

        client.getSpotExchangeInfo(List.of("BTCUSDT", "ETHUSDT"));

        assertThat(SENT_URI.get()).isNotNull();
        assertThat(SENT_URI.get().toString())
                .endsWith("/api/v3/exchangeInfo?symbols=%5B%22BTCUSDT%22,%22ETHUSDT%22%5D")
                .doesNotContain("%25");
    }

    /** alternative.me 不是 Binance：Binance 熔断期间恐惧贪婪指数照常取，不该被别家的配额连坐 */
    @Test
    void 恐惧贪婪指数不受Binance熔断阻断() {
        AtomicInteger fngCalls = new AtomicInteger();
        BinanceRestClient client = new BinanceRestClient(props()) {
            @Override
            protected String get(String uri) {
                if (uri.contains("alternative.me")) {
                    fngCalls.incrementAndGet();
                    return "{\"data\":[]}";
                }
                throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests", new HttpHeaders(), null, null);
            }
        };

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isNull();

        assertThat(client.getFearGreedIndex(2)).isEqualTo("{\"data\":[]}");
        assertThat(fngCalls.get()).isEqualTo(1);
    }

    /** 反向：免费公共 API 限流一次，不许把 Binance 行情连带停 2 分钟——策略自动交易轨也在这条线上 */
    @Test
    void 恐惧贪婪指数被限流不熔断Binance() {
        BinanceRestClient client = new BinanceRestClient(props()) {
            @Override
            protected String get(String uri) {
                if (uri.contains("alternative.me")) {
                    throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                            "Too Many Requests", new HttpHeaders(), null, null);
                }
                return "[]";
            }
        };

        assertThat(client.getFearGreedIndex(2)).isNull();

        assertThat(client.getFuturesKlines("BTCUSDT", "5m", 100, null)).isEqualTo("[]");
    }

    /**
     * 记录最终真正发出去的 URI。createRestTemplate 在父类构造期就被调用，
     * 那时匿名子类捕获的局部变量还没赋值，只能用静态字段接。
     */
    private static final AtomicReference<URI> SENT_URI = new AtomicReference<>();

    private static RestTemplate recordingRestTemplate() {
        RestTemplate rt = new RestTemplate();
        rt.setRequestFactory(new ClientHttpRequestFactory() {
            @Override
            public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
                SENT_URI.set(uri);
                throw new IOException("stub：只记 URI，不真发请求");
            }
        });
        return rt;
    }

    private static java.util.function.LongSupplier fixedClock(long value) {
        return () -> value;
    }
}
