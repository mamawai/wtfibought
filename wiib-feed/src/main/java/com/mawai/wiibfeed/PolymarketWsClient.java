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
 * 1. live-data WS: Chainlink BTC 60 秒 TWAP（官网显示的当前价）+ BTC 现货<br/>
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
 *         订阅 btc/usd 的 {@code crypto_prices_twap_sixty} 和 {@code crypto_prices_chainlink} 两个主题。
 *     </li>
 *     <li>
 *         live-data 后续每条消息进入 {@link #onLiveDataMessage(String)}，按主题分开：
 *         TWAP 进 {@link #onTwapPrice(JsonNode)}，写折线图历史并广播 {@code /topic/prediction/price}，
 *         官网 TWAP 盘显示的当前价就是这条流；现货进 {@link #onSpotPrice(JsonNode)}，只写预测员用的现货历史。
 *     </li>
 *     <li>
 *         {@link #init()} 同时启动两条文本 {@code PING} 心跳：live-data 每 5 秒、CLOB 每 10 秒。
 *         Polymarket WS 需要应用层心跳保活；心跳返回的 {@code PONG} 在 {@link WsConnection} 内过滤，
 *         不进入业务 JSON 解析。两条连接都开了 30 秒静默看门狗：PONG 不算消息，30 秒没有业务消息就重连。
 *     </li>
 *     <li>
 *         {@link #init()} 还启动每 50ms 一拍的 {@link #tick()}：先 {@link #checkRoundRotation()}（见下），
 *         再 {@link #flushBook()} 把有变化的盘口写 Redis、广播 {@code /topic/prediction/market}；
 *         每秒一次 {@link #sampleBook()}：把盘口最后更新时刻和 UP 中间价各写一笔 Redis，并补推一次当前盘口，
 *         预测员靠前者判断盘口是不是停了，靠后者看最近 30 秒赔率怎么动。
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
 *         {@code book} 全量快照进 {@link #onBookSnapshot(JsonNode)}，{@code price_change} 进 {@link #onPriceChange(JsonNode)}：
 *         只把这个 token 的买一卖一记进内存，写 Redis 和广播交给每一拍。
 *         {@code last_trade_price} 只用于右侧实时交易流，广播 {@code /topic/prediction/activity}，不参与盘口价格更新。
 *     </li>
 *     <li>
 *         每一拍里的 {@link #checkRoundRotation()}：
 *         当 {@code btc-updown-5m-{windowStart}} 发生变化时，说明进入新回合：
 *         锁定上一回合、关闭旧 CLOB、清空旧盘口并广播空盘口、创建新回合、
 *         为新回合获取 token 和 openPrice，并为上一回合轮询 closePrice 触发结算。
 *     </li>
 *     <li>
 *         {@link #pollOpenPrice(long)} 负责当前回合开盘价：
 *         每秒查一次 Polymarket crypto-price API，最多 60 次；
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
    /** 盘口最后一次收到推送的时刻，每秒采样写 Redis */
    private volatile long bookUpdatedAtMs;
    /** 盘口内存最新值，按 token 存：WS 每条消息只改这里，每拍再写 Redis、推给页面 */
    private final Map<String, Top> tops = new ConcurrentHashMap<>();
    private volatile boolean bookDirty;
    private long lastSampleSec;

    /** 一个 token 的买一卖一，null = 这一档空 */
    private record Top(BigDecimal bid, BigDecimal ask) {
        static final Top EMPTY = new Top(null, null);
    }

    private static final String LIVE_DATA_URL = "wss://ws-live-data.polymarket.com/";
    private static final String CLOB_URL = "wss://ws-subscriptions-clob.polymarket.com/ws/market";
    private static final String GAMMA_EVENT_API = "https://gamma-api.polymarket.com/events/slug/";
    private static final int WINDOW_SECONDS = 300;
    /** 官方心跳要求：live-data(RTDS) 每 5 秒发 PING，CLOB 市场频道每 10 秒 */
    private static final int LIVE_DATA_PING_SECONDS = 5;
    private static final int CLOB_PING_SECONDS = 10;
    /** 结算和官网当前价都用 Chainlink 60 秒 TWAP（市场配置 btc-5m-twap-60）；现货只给预测员 */
    private static final String TWAP_TOPIC = "crypto_prices_twap_sixty";
    private static final String SPOT_TOPIC = "crypto_prices_chainlink";
    private static final long ROUND_STREAM_MAXLEN = 1_000L;
    /** 连着这么久没收到业务消息（PONG 不算）就当断了重连：TCP 活着但不推数据时价会停住 */
    private static final long WS_MAX_IDLE_SECONDS = 30;
    /** 盘口一拍的间隔：换回合检查 + 盘口写 Redis、推页面 */
    private static final long BOOK_TICK_MS = 50;

    @PostConstruct
    public void init() {
        scheduler = Executors.newScheduledThreadPool(2,
                Thread.ofVirtual().name("polymarket-ws-", 0).factory());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        liveDataWs = new WsConnection("LiveData", () -> LIVE_DATA_URL, this::onLiveDataMessage,
                this::onLiveDataConnected, null, httpClient, scheduler, shutdown, WS_MAX_IDLE_SECONDS);

        clobWs = new WsConnection("CLOB", () -> CLOB_URL, this::onClobMessage,
                this::onClobConnected, null, httpClient, scheduler, shutdown, WS_MAX_IDLE_SECONDS);

        liveDataWs.connect();
        scheduler.scheduleAtFixedRate(() -> sendWsPing(liveDataWs), LIVE_DATA_PING_SECONDS, LIVE_DATA_PING_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> sendWsPing(clobWs), CLOB_PING_SECONDS, CLOB_PING_SECONDS, TimeUnit.SECONDS);
        Thread.startVirtualThread(() -> prepareCurrentMarket(currentWindowStart()));
        scheduler.scheduleAtFixedRate(this::tick, BOOK_TICK_MS, BOOK_TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** 一拍：先看要不要换回合，再把有变化的盘口写 Redis、推给页面，每秒采样一次 */
    private void tick() {
        checkRoundRotation();
        try {
            flushBook();
            long sec = System.currentTimeMillis() / 1000;
            if (sec != lastSampleSec) {
                lastSampleSec = sec;
                sampleBook();
            }
        } catch (Exception e) {
            log.warn("盘口刷新失败: {}", e.getMessage());
        }
    }

    /** 盘口有变化就写 Redis、推给页面 */
    private void flushBook() {
        if (!bookDirty) return;
        bookDirty = false;
        Top up = top(currentUpAssetId);
        Top down = top(currentDownAssetId);
        cacheService.putPredictionBid("UP", up.bid());
        cacheService.putPredictionAsk("UP", up.ask());
        cacheService.putPredictionBid("DOWN", down.bid());
        cacheService.putPredictionAsk("DOWN", down.ask());
        broadcastBook(up, down);
    }

    /** 盘口最后更新时刻、UP 中间价各写一笔，再推一次当前盘口给刚打开页面的人；预测员靠前两者判旧、看最近 30 秒赔率怎么动 */
    private void sampleBook() {
        long updatedAt = bookUpdatedAtMs;
        if (updatedAt <= 0) return;
        cacheService.putPredictionBookUpdatedAt(updatedAt);
        Top up = top(currentUpAssetId);
        // 一边没有报价不记
        if (up.bid() != null && up.ask() != null) {
            BigDecimal mid = up.bid().add(up.ask()).divide(BigDecimal.TWO, 4, RoundingMode.HALF_UP);
            cacheService.addPredictionUpMidPoint(System.currentTimeMillis(), mid);
        }
        broadcastBook(up, top(currentDownAssetId));
    }

    private Top top(String assetId) {
        Top t = assetId == null ? null : tops.get(assetId);
        return t != null ? t : Top.EMPTY;
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
        log.info("已发送订阅: chainlink twap + spot");
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
            tops.clear();
            bookUpdatedAtMs = 0;
            cacheService.clearPredictionPrices();
            broadcastBook(Top.EMPTY, Top.EMPTY);

            // 发事件：创建新回合（startPrice可能为null，等openPrice回填）
            publishRoundEvent("create", windowStart);
            Thread.startVirtualThread(() -> pollMarketAssets(windowStart));

            // 独立线程轮询openPrice: 每秒查一次，最多60次，有数据即停
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
        syncOfficialWindowTime(windowStart, snapshot);
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

    /**
     * 官方窗口就是 slug 里的时间戳起的整 5 分钟（eventStartTime 与之一致，event.startDate 是市场创建时间不能用）；
     * 存它的价值在上游 Date 头这份时钟基准，前端倒计时靠它校本机时钟
     */
    private void syncOfficialWindowTime(long windowStart, GammaEventSnapshot snapshot) {
        long startMs = windowStart * 1000L;
        long referenceNowMs = snapshot.upstreamTimeMs() != null
                ? snapshot.upstreamTimeMs()
                : snapshot.localReceivedTimeMs();
        cacheService.putPredictionOfficialWindow(windowStart, startMs, startMs + WINDOW_SECONDS * 1000L,
                referenceNowMs, snapshot.localReceivedTimeMs());
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
        for (int i = 0; i < 60; i++) {
            if (shutdown.get()) return;
            try { Thread.sleep(1_000); } catch (InterruptedException e) { return; }
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
        log.warn("openPrice轮询60次均未获取到: windowStart={}", windowStart);
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
            if (TWAP_TOPIC.equals(topic)) {
                onTwapPrice(msg);
            } else if (SPOT_TOPIC.equals(topic)) {
                onSpotPrice(msg);
            }
        } catch (Exception e) {
            log.warn("解析live-data消息失败: {}", e.getMessage());
        }
    }

    /** TWAP 就是官网的当前价：写折线图历史并广播给页面 */
    private void onTwapPrice(JsonNode msg) {
        BigDecimal value = msg.path("payload").path("value").asDecimal(null);
        if (value == null) return;

        long now = System.currentTimeMillis();
        cacheService.addBtcTwapPoint(now, value);
        broadcastService.broadcastPrediction("price", "{\"price\":\"" + value.toPlainString() + "\",\"ts\":" + now + "}");
    }

    /** 现货只给预测员算公平价，页面不用；时刻是 Chainlink 自己的整秒时间戳 */
    private void onSpotPrice(JsonNode msg) {
        JsonNode payload = msg.path("payload");
        BigDecimal value = payload.path("value").asDecimal(null);
        if (value == null) return;
        cacheService.addBtcPricePoint(payload.path("timestamp").asLong(), value);
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
        } else if ("book".equals(eventType)) {
            onBookSnapshot(msg);
        }
    }

    /** book 全量快照：取这一边的买一卖一 */
    private void onBookSnapshot(JsonNode msg) {
        String assetId = msg.path("asset_id").asString(null);
        if (sideForAsset(assetId) == null) return;
        updateTop(assetId, bestPrice(msg.path("bids"), true), bestPrice(msg.path("asks"), false));
    }

    /**
     * 一个 token 的买一卖一记进内存。每条消息都是这一边完整的最优价：
     * WS 用买一 0、卖一 1 表示这一档空了，和快照里的空档位一样按没有报价处理
     */
    private void updateTop(String assetId, BigDecimal bid, BigDecimal ask) {
        tops.put(assetId, new Top(bid != null && bid.signum() > 0 ? bid : null,
                ask != null && ask.compareTo(BigDecimal.ONE) < 0 ? ask : null));
        bookUpdatedAtMs = System.currentTimeMillis();
        bookDirty = true;
    }

    /** 档位数组 [{price,size}]：买盘取最高价、卖盘取最低价，不假设上游排过序；空档位回 null */
    static BigDecimal bestPrice(JsonNode levels, boolean highest) {
        BigDecimal best = null;
        for (JsonNode level : levels) {
            BigDecimal price = level.path("price").asDecimal();
            if (best == null || (highest ? price.compareTo(best) > 0 : price.compareTo(best) < 0)) {
                best = price;
            }
        }
        return best;
    }

    private void onPriceChange(JsonNode msg) {
        for (JsonNode change : msg.path("price_changes")) {
            String assetId = change.path("asset_id").asString(null);
            if (sideForAsset(assetId) == null) continue;
            updateTop(assetId, change.path("best_bid").asDecimal(null), change.path("best_ask").asDecimal(null));
        }
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

    private void broadcastBook(Top up, Top down) {
        String json = "{\"upBid\":" + quoted(up.bid()) + ",\"upAsk\":" + quoted(up.ask())
                + ",\"downBid\":" + quoted(down.bid()) + ",\"downAsk\":" + quoted(down.ask())
                + ",\"ts\":" + System.currentTimeMillis() + "}";
        broadcastService.broadcastPrediction("market", json);
    }

    private static String quoted(BigDecimal v) {
        return v == null ? "null" : "\"" + v.toPlainString() + "\"";
    }

    // ==================== 订阅消息构建 ====================

    /** TWAP + 现货两个主题，filters 是 JSON 字符串 */
    private static String buildLiveDataSubscribeMsg() {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("action", "subscribe");
        ArrayNode subs = msg.putArray("subscriptions");
        for (String topic : new String[]{TWAP_TOPIC, SPOT_TOPIC}) {
            subs.addObject().put("topic", topic).put("type", "*").put("filters", "{\"symbol\":\"btc/usd\"}");
        }
        return MAPPER.writeValueAsString(msg);
    }

}
