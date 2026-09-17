package com.mawai.wiibfeed;

import com.mawai.wiibcommon.market.PolymarketPriceClient;
import com.mawai.wiibcommon.market.PredictionStreamChannels;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * Polymarket WS 客户端
 * <p>
 * 1. live-data WS: Chainlink BTC价格<br/>
 * 2. CLOB WS: UP/DOWN 实时盘口价格 + 实时成交动态
 * <p>
 * 我们透传这些真实数据到前端展示，模拟交易按 Polymarket 实时价格成交。
 *
 * <h3>调用顺序</h3>
 * <ol>
 *     <li>
 *         Spring 创建 Bean 后调用 {@link #init()}。
 *         这里创建共享的 {@link HttpClient}、定时线程池，以及两条 WS 连接对象：
 *         live-data 用来接 BTC Chainlink 价格，CLOB 用来接当前回合 UP/DOWN 盘口。
 *     </li>
 *     <li>
 *         {@link #init()} 立即执行 {@code liveDataWs.connect()}。
 *         live-data 连接成功后回调 {@link #onLiveDataConnected(WebSocket)}，
 *         只订阅 {@code crypto_prices_chainlink}，用于页面 BTC 实时价格和折线图。
 *     </li>
 *     <li>
 *         live-data 后续每条消息进入 {@link #onLiveDataMessage(String)}。
 *         只有 {@code crypto_prices_chainlink} 会继续进入 {@link #onChainlinkPrice(JsonNode)}：
 *         写 Redis、本地缓存、价格历史，然后广播 {@code /topic/prediction/price}。
 *     </li>
 *     <li>
 *         {@link #init()} 同时启动两条 10 秒一次的文本 {@code PING} 心跳。
 *         Polymarket WS 需要应用层心跳保活；心跳返回的 {@code PONG} 在 {@link WsConnection} 内过滤，
 *         不进入业务 JSON 解析。
 *     </li>
 *     <li>
 *         {@link #init()} 启动虚拟线程执行 {@link #prepareCurrentMarket(long)}。
 *         这是服务在一个 5 分钟回合中途启动时的补偿逻辑：
 *         先创建当前回合，再准备当前回合的 CLOB token，并开始轮询 openPrice。
 *     </li>
 *     <li>
 *         {@link #prepareCurrentMarket(long)} 调用 {@link #pollMarketAssets(long)}。
 *         它每 2 秒重试一次，直到成功或本回合结束；每次通过 {@link #refreshMarketAssets(long)}
 *         访问 Gamma {@code events/slug/btc-updown-5m-{windowStart}}，
 *         从 {@code outcomes} 与 {@code clobTokenIds} 的相同下标解析出 UP/DOWN token。
 *     </li>
 *     <li>
 *         {@link #refreshMarketAssets(long)} 拿到 UP/DOWN token 后写入
 *         {@code currentUpAssetId/currentDownAssetId}，再调用 {@link #maybeConnectClobWs()}。
 *         只有两个 token 都存在且 CLOB 未连接时，才真正执行 {@code clobWs.connect()}。
 *     </li>
 *     <li>
 *         CLOB 连接成功后回调 {@link #onClobConnected(WebSocket)}。
 *         这里按 Polymarket 官方 market channel 格式订阅当前回合两个 token：
 *         {@code assets_ids=[upId, downId], type=market}。
 *     </li>
 *     <li>
 *         CLOB 后续消息进入 {@link #onClobMessage(String)}，再进入 {@link #handleClobEvent(JsonNode)}。
 *         当前价格只处理 {@code price_change}，进入 {@link #onPriceChange(JsonNode)}：
 *         根据 {@code asset_id} 判断 UP/DOWN，更新 bid/ask 缓存，并广播 {@code /topic/prediction/market}。
 *         {@code last_trade_price} 只用于右侧实时交易流，广播 {@code /topic/prediction/activity}，
 *         不参与盘口价格更新，避免重复刷价格。
 *     </li>
 *     <li>
 *         {@link #init()} 还启动每秒一次的 {@link #checkRoundRotation()}。
 *         当 {@code btc-updown-5m-{windowStart}} 发生变化时，说明进入新回合：
 *         锁定上一回合、关闭旧 CLOB、清空旧盘口并广播空盘口、创建新回合、
 *         为新回合获取 token 和 openPrice，并为上一回合轮询 closePrice 触发结算。
 *     </li>
 *     <li>
 *         {@link #pollOpenPrice(long)} 负责当前回合开盘价：
 *         每 5 秒查一次 Polymarket crypto-price API，最多 12 次；
 *         成功后写缓存并发 {@code syncopen} 事件，由 sim 回填 DB。
 *     </li>
 *     <li>
 *         {@link #pollClosePrice(long)} 负责上一回合收盘价：
 *         先等 60 秒，再每 10 秒查一次，最多 6 次；
 *         API 返回 {@code completed=true} 且有 closePrice 后，写缓存并发
 *         {@code settle} 事件，由 sim 结算上一回合。
 *     </li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolymarketWsClient implements SmartLifecycle {

    private final MarketBroadcaster broadcastService;
    private final CacheService cacheService;
    private final StringRedisTemplate redisTemplate;
    private final PolymarketPriceClient priceClient;

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private WsConnection liveDataWs;
    private WsConnection clobWs;

    // 当前回合
    private volatile String lastSubscribedSlug;
    private volatile String currentUpAssetId;
    private volatile String currentDownAssetId;

    private static final String LIVE_DATA_URL = "wss://ws-live-data.polymarket.com/";
    private static final String CLOB_URL = "wss://ws-subscriptions-clob.polymarket.com/ws/market";
    private static final String GAMMA_EVENT_API = "https://gamma-api.polymarket.com/events/slug/";
    private static final int WINDOW_SECONDS = 300;
    private static final int WS_PING_SECONDS = 10;
    private static final String CHAINLINK_REDIS_KEY = "chainlink:price:btcusd";
    private static final long ROUND_STREAM_MAXLEN = 1_000L;

    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(2,
                Thread.ofVirtual().name("polymarket-ws-", 0).factory());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        liveDataWs = new WsConnection("LiveData", () -> LIVE_DATA_URL, this::onLiveDataMessage,
                this::onLiveDataConnected, null, httpClient, scheduler, shutdown);

        clobWs = new WsConnection("CLOB", () -> CLOB_URL, this::onClobMessage,
                this::onClobConnected, null, httpClient, scheduler, shutdown);

        liveDataWs.connect();
        scheduler.scheduleAtFixedRate(() -> sendWsPing(liveDataWs), WS_PING_SECONDS, WS_PING_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> sendWsPing(clobWs), WS_PING_SECONDS, WS_PING_SECONDS, TimeUnit.SECONDS);
        Thread.startVirtualThread(() -> prepareCurrentMarket(currentWindowStart()));
        scheduler.scheduleAtFixedRate(this::checkRoundRotation, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        shutdown.set(true);
        if (liveDataWs != null) liveDataWs.close();
        if (clobWs != null) clobWs.close();
        if (scheduler != null) scheduler.shutdownNow();
        if (httpClient != null) httpClient.close();
    }

    @Override public boolean isRunning() { return !shutdown.get() && scheduler != null; }
    @Override public int getPhase() { return 1; }
    @Override public void start() { /* init via @PostConstruct */ }

    // ==================== WS 连接/重新连接 ============

    private void onLiveDataConnected(WebSocket ws) {
        ws.sendText(buildLiveDataSubscribeMsg(), true);
        log.info("已发送订阅: chainlink");
    }

    private void onClobConnected(WebSocket ws) {
        String upId = currentUpAssetId;
        String downId = currentDownAssetId;
        if (upId == null || downId == null) { clobWs.close(); return; }

        ObjectNode msg = MAPPER.createObjectNode();
        msg.putArray("assets_ids").add(upId).add(downId);
        msg.put("type", "market");
        msg.put("custom_feature_enabled", true);
        ws.sendText(MAPPER.writeValueAsString(msg), true);
        log.info("已订阅CLOB market: upAssetId={}, downAssetId={}", upId, downId);
    }

    private void sendWsPing(WsConnection connection) {
        if (!connection.isConnected()) return;
        WebSocket ws = connection.ws();
        if (ws != null) {
            try {
                ws.sendText("PING", true);
            } catch (Exception e) {
                log.debug("发送WS心跳失败: {}", e.getMessage());
            }
        }
    }

    // ==================== 回合轮换 ====================

    private static long currentWindowStart() {
        long now = Instant.now().getEpochSecond();
        return now - (now % WINDOW_SECONDS);
    }

    private static String eventSlug(long windowStart) {
        return "btc-updown-5m-" + windowStart;
    }

    private void checkRoundRotation() {
        try {
            long windowStart = currentWindowStart();
            String newSlug = eventSlug(windowStart);
            if (newSlug.equals(lastSubscribedSlug)) return;

            lastSubscribedSlug = newSlug;
            long prevWindowStart = windowStart - WINDOW_SECONDS;
            currentUpAssetId = null;
            currentDownAssetId = null;

            // 发事件：锁定上一轮 OPEN→LOCKED，禁止sell（sim 消费触发）
            publishRoundEvent("lock", prevWindowStart);

            // 关闭旧的 CLOB WS，清除旧盘口价格并通知前端
            clobWs.close();
            cacheService.clearPredictionPrices();
            broadcastPriceUpdate();

            // 发事件：创建新回合（startPrice可能为null，等openPrice回填）
            publishRoundEvent("create", windowStart);
            Thread.startVirtualThread(() -> pollMarketAssets(windowStart));

            // 独立线程轮询openPrice: 每5s查一次，最多12次，有数据即停
            Thread.startVirtualThread(() -> pollOpenPrice(windowStart));
            // 独立线程轮询closePrice: 60s后开始，每10s查一次，最多6次，有数据即停
            Thread.startVirtualThread(() -> pollClosePrice(prevWindowStart));
        } catch (Exception e) {
            log.warn("回合轮换检查失败", e);
        }
    }

    /** 回合事件发 Redis Stream（feed→sim）。顺序敏感(lock→create→settle)，Stream 保 FIFO；sim 侧 PredictionRoundConsumer 消费触发账本。 */
    private void publishRoundEvent(String type, long windowStart) {
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("type", type);
            fields.put("windowStart", Long.toString(windowStart));
            var ops = redisTemplate.opsForStream();
            ops.add(StreamRecords.newRecord().in(PredictionStreamChannels.ROUND_STREAM).ofMap(fields));
            ops.trim(PredictionStreamChannels.ROUND_STREAM, ROUND_STREAM_MAXLEN, true);  // 近似裁剪，防无限增长
        } catch (Exception e) {
            log.error("[PredictionRound] 写 Stream 失败 type={} windowStart={} msg={}", type, windowStart, e.toString());
        }
    }

    private void prepareCurrentMarket(long windowStart) {
        try {
            if (windowStart == currentWindowStart()) {
                lastSubscribedSlug = eventSlug(windowStart);
            }
            publishRoundEvent("create", windowStart);
            Thread.startVirtualThread(() -> pollOpenPrice(windowStart));
            pollMarketAssets(windowStart);
        } catch (Exception e) {
            log.warn("初始化当前预测市场失败: windowStart={}, err={}", windowStart, e.getMessage());
        }
    }

    private void pollMarketAssets(long windowStart) {
        while (!shutdown.get() && windowStart == currentWindowStart()) {
            if (refreshMarketAssets(windowStart)) return;
            try { Thread.sleep(2_000); } catch (InterruptedException e) { return; }
        }
        log.warn("当前回合CLOB token获取失败: slug={}", eventSlug(windowStart));
    }

    private boolean refreshMarketAssets(long windowStart) {
        String slug = eventSlug(windowStart);
        GammaEventSnapshot snapshot = fetchGammaEvent(slug);
        if (snapshot == null) return false;
        JsonNode event = snapshot.event();

        JsonNode market = selectClobMarket(event, slug);
        if (market == null) return false;
        syncOfficialWindowTime(windowStart, event, market, snapshot);
        // 官方时间拿到后再推一次round，避免前端初次请求早于Gamma缓存导致倒计时仍走本机取模。
        publishRoundEvent("create", windowStart);

        ArrayNode outcomes = parseJsonArrayField(market, "outcomes");
        ArrayNode tokenIds = parseJsonArrayField(market, "clobTokenIds");
        if (tokenIds == null || tokenIds.size() < 2) return false;

        String upId = null;
        String downId = null;
        if (outcomes != null) {
            int count = Math.min(outcomes.size(), tokenIds.size());
            for (int i = 0; i < count; i++) {
                String outcome = outcomes.path(i).asString(null);
                String tokenId = tokenIds.path(i).asString(null);
                if (outcome == null || tokenId == null) continue;
                if ("Up".equalsIgnoreCase(outcome)) {
                    upId = tokenId;
                } else if ("Down".equalsIgnoreCase(outcome)) {
                    downId = tokenId;
                }
            }
        }
        if (upId == null) upId = tokenIds.path(0).asString(null);
        if (downId == null) downId = tokenIds.path(1).asString(null);
        if (upId == null || downId == null) return false;
        if (windowStart != currentWindowStart()) return false;

        boolean changed = !upId.equals(currentUpAssetId) || !downId.equals(currentDownAssetId);
        currentUpAssetId = upId;
        currentDownAssetId = downId;
        if (changed && clobWs.isConnected()) {
            clobWs.close();
        }
        maybeConnectClobWs();
        log.info("当前回合CLOB token已就绪: slug={}, up={}, down={}", slug, upId, downId);
        return true;
    }

    private void syncOfficialWindowTime(long windowStart, JsonNode event, JsonNode market,
                                        GammaEventSnapshot snapshot) {
        Long startMs = parseIsoTimeMs(market.path("eventStartTime").asString(null));
        if (startMs == null) startMs = parseIsoTimeMs(event.path("startTime").asString(null));
        if (startMs == null) startMs = parseIsoTimeMs(event.path("startDate").asString(null));

        Long endMs = parseIsoTimeMs(market.path("endDate").asString(null));
        if (endMs == null) endMs = parseIsoTimeMs(event.path("endDate").asString(null));

        if (startMs != null && endMs != null) {
            long referenceNowMs = snapshot.upstreamTimeMs() != null
                    ? snapshot.upstreamTimeMs()
                    : snapshot.localReceivedTimeMs();
            cacheService.putPredictionOfficialWindow(windowStart, startMs, endMs,
                    referenceNowMs, snapshot.localReceivedTimeMs());
        }
    }

    private static Long parseIsoTimeMs(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record GammaEventSnapshot(JsonNode event, Long upstreamTimeMs, long localReceivedTimeMs) {}

    private GammaEventSnapshot fetchGammaEvent(String slug) {
        try {
            long sentAt = System.currentTimeMillis();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GAMMA_EVENT_API + slug))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long receivedAt = System.currentTimeMillis();
            if (resp.statusCode() != 200) {
                log.debug("Gamma事件查询未命中: slug={}, status={}", slug, resp.statusCode());
                return null;
            }
            Long upstreamTimeMs = resp.headers()
                    .firstValue("Date")
                    .map(PolymarketWsClient::parseHttpDateMs)
                    .orElse(null);
            if (upstreamTimeMs != null) {
                upstreamTimeMs += Math.max(0, receivedAt - sentAt) / 2;
            }
            return new GammaEventSnapshot(MAPPER.readTree(resp.body()), upstreamTimeMs, receivedAt);
        } catch (Exception e) {
            log.warn("Gamma事件查询失败: slug={}, err={}", slug, e.getMessage());
            return null;
        }
    }

    private static Long parseHttpDateMs(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonNode selectClobMarket(JsonNode event, String slug) {
        JsonNode markets = event.path("markets");
        if (markets.isEmpty()) return null;

        JsonNode fallback = null;
        for (JsonNode market : markets) {
            if (!market.isObject() || parseJsonArrayField(market, "clobTokenIds") == null) continue;
            if (fallback == null) fallback = market;
            if (slug.equals(market.path("slug").asString(null))) return market;
        }
        return fallback;
    }

    private static ArrayNode parseJsonArrayField(JsonNode obj, String field) {
        try {
            JsonNode value = obj.path(field);
            if (value instanceof ArrayNode array) return array;
            if (value.isString() && !value.asString().isBlank()) return MAPPER.readValue(value.asString(), ArrayNode.class);
        } catch (Exception ignored) {
            // Polymarket这些字段历史上有字符串/数组两种形态，解析失败按缺失处理。
        }
        return null;
    }

    private void pollOpenPrice(long windowStart) {
        for (int i = 0; i < 12; i++) {
            if (shutdown.get()) return;
            try { Thread.sleep(5_000); } catch (InterruptedException e) { return; }
            if (cacheService.getPolymarketOpenPrice(windowStart) != null) {
                log.info("openPrice已存在,跳过: windowStart={}", windowStart);
                return;
            }
            PolymarketPriceClient.CryptoPrice price = priceClient.fetch(windowStart);
            if (price == null) continue;
            BigDecimal openPrice = price.openPrice();
            if (openPrice != null) {
                cacheService.putPolymarketOpenPrice(windowStart, openPrice);
                publishRoundEvent("syncopen", windowStart);
                log.info("openPrice获取成功: windowStart={}, price={}, 第{}次", windowStart, openPrice, i + 1);
                return;
            }
        }
        log.warn("openPrice轮询12次均未获取到: windowStart={}", windowStart);
    }

    private void pollClosePrice(long prevWindowStart) {
        try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
        for (int i = 0; i < 6; i++) {
            if (shutdown.get()) return;
            if (i > 0) {
                try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
            }
            if (cacheService.getPolymarketClosePrice(prevWindowStart) != null) {
                log.info("closePrice已存在,跳过: windowStart={}", prevWindowStart);
                return;
            }
            PolymarketPriceClient.CryptoPrice price = priceClient.fetch(prevWindowStart);
            if (price == null || !price.completed()) continue;
            BigDecimal closePrice = price.closePrice();
            if (closePrice != null) {
                cacheService.putPolymarketClosePrice(prevWindowStart, closePrice);
                publishRoundEvent("settle", prevWindowStart);
                log.info("closePrice获取并结算成功: windowStart={}, price={}, 第{}次", prevWindowStart, closePrice, i + 1);
                return;
            }
        }
        log.warn("closePrice轮询6次均未获取到: windowStart={}", prevWindowStart);
    }

    // ==================== live-data 消息处理 ====================

    private void onLiveDataMessage(String raw) {
        try {
            JsonNode msg = MAPPER.readTree(raw);
            String topic = msg.path("topic").asString(null);
            if (topic == null) return;

            if ("crypto_prices_chainlink".equals(topic)) {
                onChainlinkPrice(msg);
            }
        } catch (Exception e) {
            log.warn("解析live-data消息失败: {}", e.getMessage());
        }
    }

    private void onChainlinkPrice(JsonNode msg) {
        BigDecimal value = msg.path("payload").path("value").asDecimal(null);
        if (value == null) return;

        long now = System.currentTimeMillis();
        String priceStr = value.toPlainString();
        cacheService.set(CHAINLINK_REDIS_KEY, priceStr);   // Redis KV：页面实时价
        cacheService.addBtcPricePoint(now, value);          // Redis ZSet：折线图历史

        String json = "{\"price\":\"" + priceStr + "\",\"ts\":" + now + "}";
        broadcastService.broadcastPrediction("price", json);
    }

    // ==================== CLOB WS ====================

    private void maybeConnectClobWs() {
        if (currentUpAssetId == null || currentDownAssetId == null || clobWs.isConnected()) return;
        clobWs.connect();
    }

    private void onClobMessage(String raw) {
        try {
            JsonNode parsed = MAPPER.readTree(raw);
            if (parsed.isArray()) {
                for (JsonNode event : parsed) {
                    handleClobEvent(event);
                }
            } else if (parsed.isObject()) {
                handleClobEvent(parsed);
            }
        } catch (Exception e) {
            log.warn("解析CLOB消息失败: {}", e.getMessage());
        }
    }

    private void handleClobEvent(JsonNode msg) {
        String eventType = msg.path("event_type").asString(null);
        if ("price_change".equals(eventType)) {
            onPriceChange(msg);
        } else if ("last_trade_price".equals(eventType)) {
            onLastTradePrice(msg);
        }
    }

    private void onPriceChange(JsonNode msg) {
        JsonNode changes = msg.path("price_changes");
        if (!changes.isArray()) return;

        for (JsonNode change : changes) {
            String side = sideForAsset(change.path("asset_id").asString(null));
            if (side == null) continue;

            BigDecimal bestBid = change.path("best_bid").asDecimal(null);
            BigDecimal bestAsk = change.path("best_ask").asDecimal(null);
            if (bestBid != null) cacheService.putPredictionBid(side, bestBid);
            if (bestAsk != null) cacheService.putPredictionAsk(side, bestAsk);
        }

        broadcastPriceUpdate();
    }

    private void onLastTradePrice(JsonNode msg) {
        String side = sideForAsset(msg.path("asset_id").asString(null));
        if (side == null) return;

        BigDecimal price = msg.path("price").asDecimal(null);
        BigDecimal size = msg.path("size").asDecimal(null);
        BigDecimal amount = (price != null && size != null) ? price.multiply(size) : BigDecimal.ZERO;
        String json = "{\"outcome\":\"" + ("UP".equals(side) ? "Up" : "Down")
                + "\",\"side\":\"" + side
                + "\",\"amount\":" + amount.setScale(2, RoundingMode.HALF_UP)
                + ",\"ts\":" + parseClobTimestamp(msg.path("timestamp").asString(null)) + "}";
        broadcastService.broadcastPrediction("activity", json);
    }

    private String sideForAsset(String assetId) {
        if (assetId == null) return null;
        if (assetId.equals(currentUpAssetId)) return "UP";
        if (assetId.equals(currentDownAssetId)) return "DOWN";
        return null;
    }

    private static long parseClobTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return System.currentTimeMillis();
        try {
            long ts = Long.parseLong(timestamp);
            return ts < 10_000_000_000L ? ts * 1000 : ts;
        } catch (NumberFormatException ignored) {
            return System.currentTimeMillis();
        }
    }

    private void broadcastPriceUpdate() {
        BigDecimal upBid = cacheService.getPredictionBid("UP");
        BigDecimal upAsk = cacheService.getPredictionAsk("UP");
        BigDecimal downBid = cacheService.getPredictionBid("DOWN");
        BigDecimal downAsk = cacheService.getPredictionAsk("DOWN");
        String json = "{\"upBid\":" + (upBid != null ? "\"" + upBid + "\"" : "null")
                + ",\"upAsk\":" + (upAsk != null ? "\"" + upAsk + "\"" : "null")
                + ",\"downBid\":" + (downBid != null ? "\"" + downBid + "\"" : "null")
                + ",\"downAsk\":" + (downAsk != null ? "\"" + downAsk + "\"" : "null")
                + ",\"ts\":" + System.currentTimeMillis() + "}";
        broadcastService.broadcastPrediction("market", json);
    }

    // ==================== 订阅消息构建 ====================

    private static String buildLiveDataSubscribeMsg() {
        return "{\"action\":\"subscribe\",\"subscriptions\":["
                + "{\"topic\":\"crypto_prices_chainlink\",\"type\":\"*\","
                + "\"filters\":\"{\\\"symbol\\\":\\\"btc/usd\\\"}\"}"
                + "]}";
    }

}
