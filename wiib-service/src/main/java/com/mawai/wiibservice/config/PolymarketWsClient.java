package com.mawai.wiibservice.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.PredictionService;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *         只有 {@code crypto_prices_chainlink} 会继续进入 {@link #onChainlinkPrice(JSONObject)}：
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
 *         它最多重试 10 次，每 2 秒一次；每次通过 {@link #refreshMarketAssets(long)}
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
 *         CLOB 后续消息进入 {@link #onClobMessage(String)}，再进入 {@link #handleClobEvent(JSONObject)}。
 *         当前价格只处理 {@code price_change}，进入 {@link #onPriceChange(JSONObject)}：
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
 *         成功后写缓存并调用 {@code predictionService.syncOpenPrice()} 回填 DB。
 *     </li>
 *     <li>
 *         {@link #pollClosePrice(long)} 负责上一回合收盘价：
 *         先等 60 秒，再每 10 秒查一次，最多 6 次；
 *         API 返回 {@code completed=true} 且有 closePrice 后，写缓存并调用
 *         {@code predictionService.settlePreviousRound()} 结算上一回合。
 *     </li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolymarketWsClient implements SmartLifecycle {

    private final RedisMessageBroadcastService broadcastService;
    private final CacheService cacheService;
    private final PredictionService predictionService;

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

        JSONObject msg = new JSONObject();
        JSONArray assetIds = new JSONArray();
        assetIds.add(upId);
        assetIds.add(downId);
        msg.put("assets_ids", assetIds);
        msg.put("type", "market");
        msg.put("custom_feature_enabled", true);
        ws.sendText(msg.toJSONString(), true);
        log.info("已订阅CLOB market: upAssetId={}, downAssetId={}", upId, downId);
    }

    private void sendWsPing(WsConnection connection) {
        if (connection == null || !connection.isConnected()) return;
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

            // 立即锁定上一轮 OPEN→LOCKED，禁止sell
            predictionService.lockRound(prevWindowStart);

            // 关闭旧的 CLOB WS，清除旧盘口价格并通知前端
            clobWs.close();
            cacheService.clearPredictionPrices();
            broadcastPriceUpdate();

            // 创建新回合（startPrice可能为null，等openPrice回填）
            predictionService.createNewRound();
            Thread.startVirtualThread(() -> pollMarketAssets(windowStart));

            // 独立线程轮询openPrice: 每5s查一次，最多12次，有数据即停
            Thread.startVirtualThread(() -> pollOpenPrice(windowStart));
            // 独立线程轮询closePrice: 60s后开始，每10s查一次，最多6次，有数据即停
            Thread.startVirtualThread(() -> pollClosePrice(prevWindowStart));
        } catch (Exception e) {
            log.warn("回合轮换检查失败", e);
        }
    }

    private void prepareCurrentMarket(long windowStart) {
        try {
            if (windowStart == currentWindowStart()) {
                lastSubscribedSlug = eventSlug(windowStart);
            }
            predictionService.createNewRound();
            pollMarketAssets(windowStart);
            Thread.startVirtualThread(() -> pollOpenPrice(windowStart));
        } catch (Exception e) {
            log.warn("初始化当前预测市场失败: windowStart={}, err={}", windowStart, e.getMessage());
        }
    }

    private static final String CRYPTO_PRICE_API = "https://polymarket.com/api/crypto/crypto-price";

    private void pollMarketAssets(long windowStart) {
        for (int i = 0; i < 10; i++) {
            if (shutdown.get() || windowStart != currentWindowStart()) return;
            if (refreshMarketAssets(windowStart)) return;
            try { Thread.sleep(2_000); } catch (InterruptedException e) { return; }
        }
        log.warn("当前回合CLOB token获取失败: slug={}", eventSlug(windowStart));
    }

    private boolean refreshMarketAssets(long windowStart) {
        String slug = eventSlug(windowStart);
        GammaEventSnapshot snapshot = fetchGammaEvent(slug);
        if (snapshot == null) return false;
        JSONObject event = snapshot.event();

        JSONObject market = selectClobMarket(event, slug);
        if (market == null) return false;
        syncOfficialWindowTime(windowStart, event, market, snapshot);
        // 官方时间拿到后再推一次round，避免前端初次请求早于Gamma缓存导致倒计时仍走本机取模。
        predictionService.createNewRound();

        JSONArray outcomes = parseJsonArrayField(market, "outcomes");
        JSONArray tokenIds = parseJsonArrayField(market, "clobTokenIds");
        if (tokenIds == null || tokenIds.size() < 2) return false;

        String upId = null;
        String downId = null;
        if (outcomes != null) {
            int count = Math.min(outcomes.size(), tokenIds.size());
            for (int i = 0; i < count; i++) {
                String outcome = outcomes.getString(i);
                String tokenId = tokenIds.getString(i);
                if (outcome == null || tokenId == null) continue;
                if ("Up".equalsIgnoreCase(outcome)) {
                    upId = tokenId;
                } else if ("Down".equalsIgnoreCase(outcome)) {
                    downId = tokenId;
                }
            }
        }
        if (upId == null) upId = tokenIds.getString(0);
        if (downId == null) downId = tokenIds.getString(1);
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

    private void syncOfficialWindowTime(long windowStart, JSONObject event, JSONObject market,
                                        GammaEventSnapshot snapshot) {
        Long startMs = parseIsoTimeMs(market.getString("eventStartTime"));
        if (startMs == null) startMs = parseIsoTimeMs(event.getString("startTime"));
        if (startMs == null) startMs = parseIsoTimeMs(event.getString("startDate"));

        Long endMs = parseIsoTimeMs(market.getString("endDate"));
        if (endMs == null) endMs = parseIsoTimeMs(event.getString("endDate"));

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

    private record GammaEventSnapshot(JSONObject event, Long upstreamTimeMs, long localReceivedTimeMs) {}

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
            return new GammaEventSnapshot(JSON.parseObject(resp.body()), upstreamTimeMs, receivedAt);
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

    private static JSONObject selectClobMarket(JSONObject event, String slug) {
        JSONArray markets = event.getJSONArray("markets");
        if (markets == null || markets.isEmpty()) return null;

        JSONObject fallback = null;
        for (int i = 0; i < markets.size(); i++) {
            JSONObject market = markets.getJSONObject(i);
            if (market == null || parseJsonArrayField(market, "clobTokenIds") == null) continue;
            if (fallback == null) fallback = market;
            if (slug.equals(market.getString("slug"))) return market;
        }
        return fallback;
    }

    private static JSONArray parseJsonArrayField(JSONObject obj, String field) {
        try {
            Object value = obj.get(field);
            if (value instanceof JSONArray array) return array;
            if (value instanceof String str && !str.isBlank()) return JSON.parseArray(str);
        } catch (Exception ignored) {
            // Polymarket这些字段历史上有字符串/数组两种形态，解析失败按缺失处理。
        }
        return null;
    }

    private JSONObject fetchCryptoPrice(long windowStart) {
        try {
            Instant start = Instant.ofEpochSecond(windowStart);
            Instant end = start.plusSeconds(WINDOW_SECONDS);
            URI uri = URI.create(CRYPTO_PRICE_API + "?symbol=BTC&variant=fiveminute"
                    + "&eventStartTime=" + start + "&endDate=" + end);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            return JSON.parseObject(resp.body());
        } catch (Exception e) {
            log.warn("Polymarket API调用失败: windowStart={}, err={}", windowStart, e.getMessage());
            return null;
        }
    }

    private void pollOpenPrice(long windowStart) {
        for (int i = 0; i < 12; i++) {
            if (shutdown.get()) return;
            try { Thread.sleep(5_000); } catch (InterruptedException e) { return; }
            if (cacheService.getPolymarketOpenPrice(windowStart) != null) {
                log.info("openPrice已存在,跳过: windowStart={}", windowStart);
                return;
            }
            JSONObject json = fetchCryptoPrice(windowStart);
            if (json == null) continue;
            BigDecimal openPrice = json.getBigDecimal("openPrice");
            if (openPrice != null) {
                cacheService.putPolymarketOpenPrice(windowStart, openPrice);
                predictionService.syncOpenPrice();
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
            JSONObject json = fetchCryptoPrice(prevWindowStart);
            if (json == null) continue;
            if (!Boolean.TRUE.equals(json.getBoolean("completed"))) continue;
            BigDecimal closePrice = json.getBigDecimal("closePrice");
            if (closePrice != null) {
                cacheService.putPolymarketClosePrice(prevWindowStart, closePrice);
                predictionService.settlePreviousRound();
                log.info("closePrice获取并结算成功: windowStart={}, price={}, 第{}次", prevWindowStart, closePrice, i + 1);
                return;
            }
        }
        log.warn("closePrice轮询6次均未获取到: windowStart={}", prevWindowStart);
    }

    // ==================== live-data 消息处理 ====================

    private void onLiveDataMessage(String raw) {
        try {
            JSONObject msg = JSON.parseObject(raw);
            if (msg == null) return;
            String topic = msg.getString("topic");
            if (topic == null) return;

            if ("crypto_prices_chainlink".equals(topic)) {
                onChainlinkPrice(msg);
            }
        } catch (Exception e) {
            log.warn("解析live-data消息失败: {}", e.getMessage());
        }
    }

    private void onChainlinkPrice(JSONObject msg) {
        JSONObject payload = msg.getJSONObject("payload");
        if (payload == null) return;

        BigDecimal value = payload.getBigDecimal("value");
        if (value == null) return;

        long now = System.currentTimeMillis();
        String priceStr = value.toPlainString();
        // redis
        cacheService.set(CHAINLINK_REDIS_KEY, priceStr);
        // caffeine
        cacheService.putChainlinkPrice("btcusd", value);
        cacheService.addBtcPricePoint(now, value);

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
            Object parsed = JSON.parse(raw);
            if (parsed instanceof JSONArray array) {
                for (int i = 0; i < array.size(); i++) {
                    handleClobEvent(array.getJSONObject(i));
                }
            } else if (parsed instanceof JSONObject obj) {
                handleClobEvent(obj);
            }
        } catch (Exception e) {
            log.warn("解析CLOB消息失败: {}", e.getMessage());
        }
    }

    private void handleClobEvent(JSONObject msg) {
        if (msg == null) return;
        String eventType = msg.getString("event_type");
        if ("price_change".equals(eventType)) {
            onPriceChange(msg);
        } else if ("last_trade_price".equals(eventType)) {
            onLastTradePrice(msg);
        }
    }

    private void onPriceChange(JSONObject msg) {
        JSONArray changes = msg.getJSONArray("price_changes");
        if (changes == null) return;

        for (int i = 0; i < changes.size(); i++) {
            JSONObject change = changes.getJSONObject(i);
            String side = sideForAsset(change.getString("asset_id"));
            if (side == null) continue;

            BigDecimal bestBid = change.getBigDecimal("best_bid");
            BigDecimal bestAsk = change.getBigDecimal("best_ask");
            if (bestBid != null) cacheService.putPredictionBid(side, bestBid);
            if (bestAsk != null) cacheService.putPredictionAsk(side, bestAsk);
        }

        broadcastPriceUpdate();
    }

    private void onLastTradePrice(JSONObject msg) {
        String side = sideForAsset(msg.getString("asset_id"));
        if (side == null) return;

        BigDecimal price = msg.getBigDecimal("price");
        BigDecimal size = msg.getBigDecimal("size");
        BigDecimal amount = (price != null && size != null) ? price.multiply(size) : BigDecimal.ZERO;
        String json = "{\"outcome\":\"" + ("UP".equals(side) ? "Up" : "Down")
                + "\",\"side\":\"" + side
                + "\",\"amount\":" + amount.setScale(2, RoundingMode.HALF_UP)
                + ",\"ts\":" + parseClobTimestamp(msg.getString("timestamp")) + "}";
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
