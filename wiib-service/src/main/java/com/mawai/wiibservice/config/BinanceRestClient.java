package com.mawai.wiibservice.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;

import java.math.BigDecimal;
import java.net.URI;

@Slf4j
@Component
public class BinanceRestClient extends BaseRestTemplateConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final BinanceProperties props;
    private final CoinDeskProperties coinDeskProperties;

    public BinanceRestClient(BinanceProperties props, CoinDeskProperties coinDeskProperties) {
        this.props = props;
        this.coinDeskProperties = coinDeskProperties;
        this.restTemplate = createRestTemplate(5000, 10000);
    }

    /**
     * 拉取K线数据，直接返回Binance原始JSON字符串
     * @param symbol   交易对，如 BTCUSDT
     * @param interval K线间隔，如 1m, 5m, 1h
     * @param limit    数量，最大1000
     * @param endTime  截止时间戳(ms)，null则取最新
     */
    public String getKlines(String symbol, String interval, int limit, Long endTime) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(props.getRestBaseUrl() + "/api/v3/klines")
                .queryParam("symbol", symbol)
                .queryParam("interval", interval)
                .queryParam("limit", Math.min(limit, 1000));
        if (endTime != null) {
            builder.queryParam("endTime", endTime);
        }
        URI uri = builder.build().toUri();
        log.info("Binance REST klines: {}", uri);
        return restTemplate.getForObject(uri, String.class);
    }

    public String getKlinesLight(String symbol, String interval, int limit, Long endTime) {
        String raw = getKlines(symbol, interval, limit, endTime);
        try {
            JSONArray root = JSON.parseArray(raw);
            JSONArray result = new JSONArray(root.size());
            for (int i = 0; i < root.size(); i++) {
                JSONArray kline = root.getJSONArray(i);
                JSONArray slim = new JSONArray(5);
                for (int j = 0; j <= 4; j++) slim.add(kline.get(j));
                result.add(slim);
            }
            return result.toJSONString();
        } catch (Exception e) {
            log.warn("klines精简失败，返回原始数据", e);
            return raw;
        }
    }

    public String getFuturesKlinesLight(String symbol, String interval, int limit, Long endTime) {
        String raw = getFuturesKlines(symbol, interval, limit, endTime);
        try {
            JSONArray root = JSON.parseArray(raw);
            JSONArray result = new JSONArray(root.size());
            for (int i = 0; i < root.size(); i++) {
                JSONArray kline = root.getJSONArray(i);
                JSONArray slim = new JSONArray(5);
                for (int j = 0; j <= 4; j++) slim.add(kline.get(j));
                result.add(slim);
            }
            return result.toJSONString();
        } catch (Exception e) {
            log.warn("futures klines精简失败，返回原始数据", e);
            return raw;
        }
    }

    /**
     * 获取最新价格（WS断线兜底用）
     */
    public String getTickerPrice(String symbol) {
        URI uri = UriComponentsBuilder
                .fromUriString(props.getRestBaseUrl() + "/api/v3/ticker/price")
                .queryParam("symbol", symbol)
                .build().toUri();
        return restTemplate.getForObject(uri, String.class);
    }

    /**
     * 拉取最近1分钟 K线，返回 [periodLow, periodHigh]
     */
    public BigDecimal[] getRecentHighLow(String symbol) {
        try {
            String json = getKlines(symbol, "1s", 60, null);
            return getHighLow(json);
        } catch (Exception e) {
            log.error("解析klines高低价失败 symbol={}", symbol, e);
            return null;
        }
    }

    /**
     * 拉取future最近5分钟 K线，返回 [periodLow, periodHigh]
     */
    public BigDecimal[] getRecentFuturesHighLow(String symbol) {
        try {
            String json = getFuturesKlines(symbol, "1m", 5, null);
            return getHighLow(json);
        } catch (Exception e) {
            log.error("获取合约K线高低价失败 symbol={}", symbol, e);
            return null;
        }
    }

    public String getMarkPrice(String symbol) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/fapi/v1/premiumIndex")
                .queryParam("symbol", symbol)
                .build().toUri();
        return restTemplate.getForObject(uri, String.class);
    }

    /**
     * 拉取最近2分钟Mark Price K线，返回 [periodLow, periodHigh]
     */
    public BigDecimal[] getRecentMarkPriceHighLow(String symbol) {
        try {
            String baseUrl = props.getFuturesRestBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) return null;
            URI uri = UriComponentsBuilder
                    .fromUriString(baseUrl + "/fapi/v1/markPriceKlines")
                    .queryParam("symbol", symbol)
                    .queryParam("interval", "1m")
                    .queryParam("limit", 2)
                    .build().toUri();
            String json = restTemplate.getForObject(uri, String.class);
            return getHighLow(json);
        } catch (Exception e) {
            log.error("获取Mark Price高低价失败 symbol={}", symbol, e);
            return null;
        }
    }

    /**
     * 获取24h行情（涨跌幅/成交量/最高最低）
     */
    public String get24hTicker(String symbol) {
        URI uri = UriComponentsBuilder
                .fromUriString(props.getRestBaseUrl() + "/api/v3/ticker/24hr")
                .queryParam("symbol", symbol)
                .build().toUri();
        log.info("Binance REST 24hTicker: {}", uri);
        return restTemplate.getForObject(uri, String.class);
    }

    /**
     * 获取资金费率
     */
    public String getFundingRate(String symbol) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/fapi/v1/premiumIndex")
                .queryParam("symbol", symbol)
                .build().toUri();
        log.info("Binance REST fundingRate: {}", uri);
        return restTemplate.getForObject(uri, String.class);
    }

    public String getOrderbook(String symbol, int limit) {
        URI uri = UriComponentsBuilder
                .fromUriString(props.getRestBaseUrl() + "/api/v3/depth")
                .queryParam("symbol", symbol)
                .queryParam("limit", Math.min(limit, 400))
                .build().toUri();
        try {
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("获取{}盘口失败: {}", symbol, e.getMessage());
            return null;
        }
    }

    public String getOpenInterest(String symbol) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/fapi/v1/openInterest")
                .queryParam("symbol", symbol)
                .build().toUri();
        try {
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("获取{}持仓量失败: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * 拉取OI历史数据（/futures/data/openInterestHist）。
     * 用于计算OI变化率。
     *
     * @param symbol 交易对，如 BTCUSDT
     * @param period 周期: "5m","15m","30m","1h" 等
     * @param limit  条数，默认30，最大500
     * @return JSON数组字符串，每条含 sumOpenInterest, timestamp
     */
    public String getOpenInterestHist(String symbol, String period, int limit) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/futures/data/openInterestHist")
                .queryParam("symbol", symbol)
                .queryParam("period", period)
                .queryParam("limit", limit)
                .build().toUri();
        try {
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("获取{}OI历史失败: {}", symbol, e.getMessage());
            return null;
        }
    }

    public String getLongShortRatio(String symbol) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/futures/data/globalLongShortAccountRatio")
                .queryParam("symbol", symbol)
                .queryParam("period", "5m")
                .queryParam("limit", 24)
                .build().toUri();
        try {
            log.info("Binance REST longShortRatio: {}", uri);
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("获取{}多空比失败: {}", symbol, e.getMessage());
            return null;
        }
    }

    private static final String NEWS_API_URL = "https://data-api.coindesk.com/news/v1/article/list";
    private static final String NEWS_API_ARTICLE_URL = "https://data-api.coindesk.com/news/v1/article/get";

    public String getCryptoNews(String symbol, int limit, String lang) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(NEWS_API_URL)
                .queryParam("categories", symbol)
                .queryParam("limit", limit)
                .queryParam("lang", lang)
                .queryParam("api_key", coinDeskProperties.getNewsApiKey());
        try {
            URI uri = builder.build().toUri();
            log.info("CryptoNews API: {}", uri);
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("getCryptoNews failed: {}", e.getMessage());
            return null;
        }
    }

    public String getArticleDetail(String sourceKey, String guid) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(NEWS_API_ARTICLE_URL)
                .queryParam("source_key", sourceKey)
                .queryParam("guid", guid)
                .queryParam("api_key", coinDeskProperties.getNewsApiKey());
        try {
            URI uri = builder.build().toUri();
            log.info("Article detail API: {}", uri);
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("getArticleDetail failed: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 新增数据源 ====================

    private static final String FEAR_GREED_URL = "https://api.alternative.me/fng/";

    public String getTopTraderPositionRatio(String symbol, String period, int limit) {
        return callFuturesApi("/futures/data/topLongShortPositionRatio", "topTraderPosition",
                b -> b.queryParam("symbol", symbol).queryParam("period", period).queryParam("limit", limit));
    }

    public String getTakerLongShortRatio(String symbol, String period, int limit) {
        return callFuturesApi("/futures/data/takerlongshortRatio", "takerLongShortRatio",
                b -> b.queryParam("symbol", symbol).queryParam("period", period).queryParam("limit", limit));
    }

    public String getFearGreedIndex(int limit) {
        URI uri = UriComponentsBuilder
                .fromUriString(FEAR_GREED_URL)
                .queryParam("limit", limit)
                .queryParam("format", "json")
                .build().toUri();
        try {
            log.info("FearGreed API: {}", uri);
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("获取恐惧贪婪指数失败: {}", e.getMessage());
            return null;
        }
    }

    private String callFuturesApi(String endpoint, String logLabel,
                                   java.util.function.Consumer<UriComponentsBuilder> paramConfigurer) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + endpoint);
        paramConfigurer.accept(builder);
        URI uri = builder.build().toUri();
        try {
            log.info("Binance REST {}: {}", logLabel, uri);
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("获取{}失败: {}", logLabel, e.getMessage());
            return null;
        }
    }

    // ==================== 合约专用 API ====================

    public String getFuturesKlines(String symbol, String interval, int limit, Long endTime) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return getKlines(symbol, interval, limit, endTime);
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(baseUrl + "/fapi/v1/klines")
                .queryParam("symbol", symbol)
                .queryParam("interval", interval)
                .queryParam("limit", Math.min(limit, 1500));
        if (endTime != null) {
            builder.queryParam("endTime", endTime);
        }
        URI uri = builder.build().toUri();
        log.info("Binance futures klines: {}", uri);
        return restTemplate.getForObject(uri, String.class);
    }

    public String getFutures24hTicker(String symbol) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return get24hTicker(symbol);
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/fapi/v1/ticker/24hr")
                .queryParam("symbol", symbol)
                .build().toUri();
        log.info("Binance futures 24hTicker: {}", uri);
        return restTemplate.getForObject(uri, String.class);
    }

    public String getFuturesOrderbook(String symbol, int limit) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return getOrderbook(symbol, limit);
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/fapi/v1/depth")
                .queryParam("symbol", symbol)
                .queryParam("limit", Math.min(limit, 1000))
                .build().toUri();
        try {
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("获取{}合约盘口失败: {}", symbol, e.getMessage());
            return null;
        }
    }

    public String getFundingRateHistory(String symbol, int limit) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/fapi/v1/fundingRate")
                .queryParam("symbol", symbol)
                .queryParam("limit", Math.min(limit, 100))
                .build().toUri();
        try {
            log.info("Binance REST fundingRateHistory: {}", uri);
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            log.warn("获取{}资金费率历史失败: {}", symbol, e.getMessage());
            return null;
        }
    }

    private BigDecimal[] getHighLow(String json) throws JsonProcessingException {
        if (json == null || json.isBlank()) return null;
        JsonNode root = MAPPER.readTree(json);
        BigDecimal high = null, low = null;
        for (JsonNode kline : root) {
            BigDecimal h = new BigDecimal(kline.get(2).asText());
            BigDecimal l = new BigDecimal(kline.get(3).asText());
            if (high == null || h.compareTo(high) > 0) high = h;
            if (low == null || l.compareTo(low) < 0) low = l;
        }
        return high != null ? new BigDecimal[]{low, high} : null;
    }
}
