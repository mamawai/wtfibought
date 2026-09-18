package com.mawai.wiibcommon.market;
import com.mawai.wiibcommon.config.BaseRestTemplateConfig;
import com.mawai.wiibcommon.config.BinanceProperties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

@Slf4j
@Component
public class BinanceRestClient extends BaseRestTemplateConfig {

    private static final int LONG_SHORT_RATIO_5M_LIMIT = 288; // 5m * 288 = 24h，用于LSR滚动百分位
    private final RestTemplate restTemplate;
    private final BinanceProperties props;

    /** 冷却时长：Binance 的 418 最短 2 分钟，冷却期设短了等于没熔断 */
    public static final long COOLDOWN_MS = 120_000L;

    /** 墙钟注入点：熔断的冷却判断要可测 */
    LongSupplier nowMs = System::currentTimeMillis;

    /**
     * 熔断截止时刻（0=未熔断），多线程共享。写入用 accumulateAndGet 取 max 只往后推——
     * 多个线程可能同时撞 429，先算出来的小值别把后算出来的大值盖掉、把冷却期缩短。
     */
    private final AtomicLong blockedUntil = new AtomicLong();

    public BinanceRestClient(BinanceProperties props) {
        this.props = props;
        this.restTemplate = createRestTemplate(5000, 10000);
    }

    /**
     * 裸网络调用，也是测试的接缝（Binance 请求经 getGuarded 进来，第三方 API 直接调）。
     * 这里必须走 {@code getForObject(URI, ...)}：
     * String 重载会过 RestTemplate 的 UriTemplateHandler（默认 URI_COMPONENT 模式）再编码一遍，
     * 把已经 encode 过的 %5B 变成 %255B，Binance 收到字面量直接 400。
     */
    protected String get(String uri) {
        return restTemplate.getForObject(URI.create(uri), String.class);
    }

    /**
     * Binance 取数的唯一出口。撞到 429/418 就熔断一段时间：
     * 继续按原速打只会把"慢点"催成"封 IP"，而封的是整个进程的出口 IP——
     * 策略执行轨和对话轨共用同一个客户端，一起瘫。
     * 失败/熔断期一律返回 null，下游（CollectDataNode.safeGet / MarketDataService.rawCached）已按 null 降级。
     */
    private String getGuarded(String uri) {
        long now = nowMs.getAsLong();
        long until = blockedUntil.get();
        if (now < until) {
            // 补漏按 symbol 循环调用，冷却期内 warn 会刷屏；熔断触发那一刻已经 error 记过一次了
            log.debug("Binance 限流冷却中，跳过请求（剩余 {}ms）: {}", until - now, uri);
            return null;
        }
        try {
            return get(uri);
        } catch (HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            if (code == 429 || code == 418) {
                // now 是入口处读的，这里必须现读：请求本身可能耗了 10s，用旧时间戳会把冷却期截短
                blockedUntil.accumulateAndGet(nowMs.getAsLong() + COOLDOWN_MS, Math::max);
                log.error("Binance 限流 {}，熔断 {}ms —— 继续打会升级成 IP ban 并连累策略轨", code, COOLDOWN_MS);
            } else {
                log.warn("Binance 请求失败 {}: {}", code, uri);
            }
            return null;
        } catch (Exception e) {
            log.warn("Binance 请求异常: {}", uri, e);
            return null;
        }
    }

    private String getGuarded(URI uri) {
        return getGuarded(uri.toString());
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
        return getGuarded(uri);
    }

    public String getKlinesLight(String symbol, String interval, int limit, Long endTime) {
        String raw = getKlines(symbol, interval, limit, endTime);
        try {
            return getSlimKlines(raw);
        } catch (Exception e) {
            log.warn("klines精简失败，返回原始数据", e);
            return raw;
        }
    }

    public String getFuturesKlinesLight(String symbol, String interval, int limit, Long endTime) {
        String raw = getFuturesKlines(symbol, interval, limit, endTime);
        try {
            return getSlimKlines(raw);
        } catch (Exception e) {
            log.warn("futures klines精简失败，返回原始数据", e);
            return raw;
        }
    }

    private String getSlimKlines(String raw) {
        ArrayNode root = MAPPER.readValue(raw, ArrayNode.class);
        ArrayNode result = MAPPER.createArrayNode();
        for (JsonNode kline : root) {
            ArrayNode slim = result.addArray();
            // 保留 0-7（时间/OHLC/量/收盘时间/额）：前端蜡烛图按 Binance 原始下标取 k[5]=量、k[7]=额，
            // 只裁 8-11（笔数/taker 量额/保留位）；早期裁到 0-4 导致历史K线量额全 NaN
            for (int j = 0; j <= 7; j++) slim.add(kline.get(j));
        }
        return MAPPER.writeValueAsString(result);
    }

    /**
     * 获取最新价格（缓存没热时按 symbol 现取）
     */
    public String getTickerPrice(String symbol) {
        URI uri = UriComponentsBuilder
                .fromUriString(props.getRestBaseUrl() + "/api/v3/ticker/price")
                .queryParam("symbol", symbol)
                .build().toUri();
        return getGuarded(uri);
    }

    /** 现货 1m K 线，空窗 [fromMs, toMs] 补漏用 */
    public List<KlineBar> spotBars1m(String symbol, long fromMs, long toMs) {
        return bars1m(props.getRestBaseUrl(), "/api/v3/klines", symbol, fromMs, toMs);
    }

    /** 合约最新价 1m K 线 */
    public List<KlineBar> futuresBars1m(String symbol, long fromMs, long toMs) {
        return bars1m(props.getFuturesRestBaseUrl(), "/fapi/v1/klines", symbol, fromMs, toMs);
    }

    /** 标记价 1m K 线 */
    public List<KlineBar> markBars1m(String symbol, long fromMs, long toMs) {
        return bars1m(props.getFuturesRestBaseUrl(), "/fapi/v1/markPriceKlines", symbol, fromMs, toMs);
    }

    /**
     * 按时间段拉 1m K 线。startTime 往下取整到整分（那一分钟整根算进来，宁多不漏）。
     * 空窗由调用方限在 1h 内（MatchPriceConsumer），一页装得下。请求失败/空回包 → 空列表。
     */
    private List<KlineBar> bars1m(String baseUrl, String path, String symbol, long fromMs, long toMs) {
        if (baseUrl == null || baseUrl.isBlank()) return List.of();
        long startTime = fromMs / 60_000L * 60_000L;
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + path)
                .queryParam("symbol", symbol)
                .queryParam("interval", "1m")
                .queryParam("startTime", startTime)
                .queryParam("endTime", toMs)
                .queryParam("limit", (toMs - startTime) / 60_000L + 1)
                .build().toUri();
        log.info("Binance REST 补漏K线: {}", uri);
        return KlineHistoryStore.parseRawFuturesKlines(getGuarded(uri));
    }

    /** 合约 exchangeInfo（全量，含各 symbol 的 LOT_SIZE/MIN_NOTIONAL 过滤器）；失败返回 null。 */
    public String getFuturesExchangeInfo() {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/fapi/v1/exchangeInfo").build().toUri();
        log.info("Binance REST futures exchangeInfo");
        return getGuarded(uri);
    }

    /** 现货 exchangeInfo（按 symbols 过滤，含 LOT_SIZE/NOTIONAL 过滤器）；失败返回 null。 */
    public String getSpotExchangeInfo(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return null;
        String arr = symbols.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",", "[", "]"));
        URI uri = UriComponentsBuilder
                .fromUriString(props.getRestBaseUrl() + "/api/v3/exchangeInfo")
                .queryParam("symbols", arr)
                .encode()
                .build().toUri();
        log.info("Binance REST spot exchangeInfo: {} symbols", symbols.size());
        return getGuarded(uri);
    }

    /** premiumIndex 单次响应同时含 markPrice/indexPrice/lastFundingRate，mark 价与资金费率调用方共用。 */
    public String getPremiumIndex(String symbol) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/fapi/v1/premiumIndex")
                .queryParam("symbol", symbol)
                .build().toUri();
        return getGuarded(uri);
    }

    /** premiumIndex 不带 symbol：一次返回全部合约的数组，资金费率结算点全量拉取用。 */
    public String getPremiumIndexAll() {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/fapi/v1/premiumIndex").build().toUri();
        log.info("Binance REST premiumIndex 全量");
        return getGuarded(uri);
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
        return getGuarded(uri);
    }

    /**
     * 批量 24h 行情：/api/v3/ticker/24hr?symbols=["A","B"]，一次拉多只（bStock 列表页用）。
     * 失败返回 null，调用方降级为逐只 Redis 现价。
     */
    public String get24hTickers(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return null;
        String arr = symbols.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",", "[", "]"));
        URI uri = UriComponentsBuilder
                .fromUriString(props.getRestBaseUrl() + "/api/v3/ticker/24hr")
                .queryParam("symbols", arr)
                .encode()
                .build().toUri();
        log.info("Binance REST 24hTickers: {} symbols", symbols.size());
        return getGuarded(uri);
    }


    public String getOrderbook(String symbol, int limit) {
        URI uri = UriComponentsBuilder
                .fromUriString(props.getRestBaseUrl() + "/api/v3/depth")
                .queryParam("symbol", symbol)
                .queryParam("limit", Math.min(limit, 400))
                .build().toUri();
        return getGuarded(uri);
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
        return getGuarded(uri);
    }

    public String getLongShortRatio(String symbol) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/futures/data/globalLongShortAccountRatio")
                .queryParam("symbol", symbol)
                .queryParam("period", "5m")
                .queryParam("limit", LONG_SHORT_RATIO_5M_LIMIT)
                .build().toUri();
        log.info("Binance REST longShortRatio: {}", uri);
        return getGuarded(uri);
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

    /**
     * 恐惧贪婪指数打的是 alternative.me，不是 Binance，所以刻意绕开熔断器：
     * 它是个免费公共 API，被它限流一次就冻结全部 Binance 行情 2 分钟（连策略自动交易轨一起），
     * 拿不相干供应商的配额去停自家主链路，比这个任务要防的故障还糟。
     * 失败仍归一成 null（CollectDataNode 按 null 降级），只是不写 blockedUntil。
     */
    public String getFearGreedIndex(int limit) {
        URI uri = UriComponentsBuilder
                .fromUriString(FEAR_GREED_URL)
                .queryParam("limit", limit)
                .queryParam("format", "json")
                .build().toUri();
        try {
            log.info("FearGreed API: {}", uri);
            return get(uri.toString());
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
        log.info("Binance REST {}: {}", logLabel, uri);
        return getGuarded(uri);
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
        return getGuarded(uri);
    }

    public String getFutures24hTicker(String symbol) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return get24hTicker(symbol);
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/fapi/v1/ticker/24hr")
                .queryParam("symbol", symbol)
                .build().toUri();
        log.info("Binance futures 24hTicker: {}", uri);
        return getGuarded(uri);
    }

    public String getFuturesOrderbook(String symbol, int limit) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return getOrderbook(symbol, limit);
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/fapi/v1/depth")
                .queryParam("symbol", symbol)
                .queryParam("limit", Math.min(limit, 1000))
                .build().toUri();
        return getGuarded(uri);
    }

    public String getFundingRateHistory(String symbol, int limit) {
        String baseUrl = props.getFuturesRestBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return null;
        URI uri = UriComponentsBuilder
                .fromUriString(baseUrl + "/fapi/v1/fundingRate")
                .queryParam("symbol", symbol)
                .queryParam("limit", Math.min(limit, 100))
                .build().toUri();
        log.info("Binance REST fundingRateHistory: {}", uri);
        return getGuarded(uri);
    }

}
