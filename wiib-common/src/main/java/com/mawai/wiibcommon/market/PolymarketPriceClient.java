package com.mawai.wiibcommon.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * Polymarket 5 分钟回合的开/收盘价。feed 轮询取价写缓存，sim 结算时缓存缺价回源，共用这一份。
 */
@Slf4j
@Component
public class PolymarketPriceClient {

    /** 一次响应里开收盘价都有；completed=false 表示这个窗口还没收官，closePrice 不可信 */
    public record CryptoPrice(BigDecimal openPrice, BigDecimal closePrice, boolean completed) {}

    private static final String CRYPTO_PRICE_API = "https://polymarket.com/api/crypto/crypto-price";
    private static final int WINDOW_SECONDS = 300;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 超时/非200/解析失败一律返回 null，调用方按"没拿到价"降级 */
    public CryptoPrice fetch(long windowStart) {
        try {
            Instant start = Instant.ofEpochSecond(windowStart);
            Instant end = start.plusSeconds(WINDOW_SECONDS);
            URI uri = URI.create(CRYPTO_PRICE_API + "?symbol=BTC&variant=fiveminute"
                    + "&eventStartTime=" + start + "&endDate=" + end);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonNode json = MAPPER.readTree(resp.body());
            if (!json.isObject()) return null;
            return new CryptoPrice(
                    json.path("openPrice").asDecimal(null),
                    json.path("closePrice").asDecimal(null),
                    json.path("completed").asBoolean(false));
        } catch (Exception e) {
            log.warn("Polymarket取价失败: windowStart={}, err={}", windowStart, e.getMessage());
            return null;
        }
    }
}
