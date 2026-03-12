package com.mawai.wiibservice.config;

import com.mawai.wiibservice.service.CryptoOrderService;
import com.mawai.wiibservice.service.FuturesLiquidationService;
import com.mawai.wiibservice.service.FuturesService;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceWsClient {

    private final BinanceProperties props;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageBroadcastService broadcastService;
    private final BinanceRestClient restClient;
    private final CryptoOrderService cryptoOrderService;
    private final FuturesLiquidationService futuresLiquidationService;
    private final FuturesService futuresService;

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> fallbackTask;
    private ScheduledFuture<?> futuresFallbackTask;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private WsConnection spotWs;
    private WsConnection futuresWs;

    private static final int[] BACKOFF_SECONDS = {1, 2, 5, 10, 30};
    private static final String REDIS_KEY_PREFIX = "market:price:";
    private static final String REDIS_MARK_PRICE_KEY_PREFIX = "market:markprice:";

    @PostConstruct
    public void init() {
        if (props.getSymbols() == null || props.getSymbols().isEmpty()) {
            log.warn("binance.symbols未配置，跳过WS连接");
            return;
        }
        // 虚拟线程工厂做调度池，轻量不占平台线程
        scheduler = Executors.newScheduledThreadPool(3,
                Thread.ofVirtual().name("binance-ws-", 0).factory());
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 初始化
        spotWs = new WsConnection("Spot", this::buildSpotUrl, this::onSpotMessage, this::onSpotConnected);
        futuresWs = new WsConnection("Futures", this::buildFuturesUrl, this::onFuturesMessage, this::onFuturesConnected);

        // 启动ws
        spotWs.connect();
        futuresWs.connect();
    }

    @PreDestroy
    public void destroy() {
        shutdown.set(true);
        stopFallbackPolling();
        stopFuturesFallbackPolling();
        if (spotWs != null) spotWs.close();
        if (futuresWs != null) futuresWs.close();
        if (scheduler != null) scheduler.shutdownNow();
        if (httpClient != null) httpClient.close();
    }

    public boolean isConnected() {
        return spotWs != null && spotWs.connected.get();
    }

    public boolean isFuturesConnected() {
        return futuresWs != null && futuresWs.connected.get();
    }

    // ── Spot ──

    private String buildSpotUrl() {
        String streams = props.getSymbols().stream()
                .map(s -> s.toLowerCase() + "@miniTicker")
                .reduce((a, b) -> a + "/" + b).orElse("");
        if (props.getSymbols().size() == 1) {
            return props.getWsUrl() + "/" + streams;
        }
        return props.getWsUrl().replace("/ws", "/stream?streams=" + streams);
    }

    private void onSpotConnected() {
        stopFallbackPolling();
        // 重连后用REST拉最近高低价，补漏离线期间错过的限价单
        Thread.startVirtualThread(this::recoverMissedLimitOrders);
    }

    private void onSpotMessage(String raw) {
        // 手动indexOf解析避免Jackson反序列化开销
        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = extractQuoted(raw, sIdx + 5);

        int cIdx = raw.indexOf("\"c\":\"");
        if (cIdx < 0) return;
        String price = extractQuoted(raw, cIdx + 5);

        // 提取服务端时间戳，缺失则取本地时间
        int eIdx = raw.indexOf("\"E\":");
        long ts = System.currentTimeMillis();
        if (eIdx >= 0) {
            int eStart = eIdx + 4;
            int eEnd = raw.indexOf(',', eStart);
            if (eEnd < 0) eEnd = raw.indexOf('}', eStart);
            ts = Long.parseLong(raw.substring(eStart, eEnd).trim());
        }

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + symbol, price);
        String markPrice = redisTemplate.opsForValue().get(REDIS_MARK_PRICE_KEY_PREFIX + symbol);
        String msg = "{\"price\":\"" + price + "\",\"ts\":" + ts
                + ",\"ws\":" + isConnected() + ",\"fws\":" + isFuturesConnected()
                + (markPrice != null ? ",\"mp\":\"" + markPrice + "\"" : "") + "}";
        broadcastService.broadcastCryptoQuote(symbol, msg);

        // 虚拟线程跑业务逻辑，不阻塞WS接收线程
        BigDecimal bd = new BigDecimal(price);
        Thread.startVirtualThread(() -> {
            try { cryptoOrderService.onPriceUpdate(symbol, bd); }
            catch (Exception e) { log.warn("crypto限价单检查异常 {}: {}", symbol, e.getMessage()); }
        });
        Thread.startVirtualThread(() -> {
            try { futuresService.onPriceUpdate(symbol, bd); }
            catch (Exception e) { log.warn("futures限价单检查异常 {}: {}", symbol, e.getMessage()); }
        });
    }

    // ── Futures ──

    private void onFuturesConnected() {
        stopFuturesFallbackPolling();
        // 重连后补漏离线期间的强平检查
        Thread.startVirtualThread(this::recoverMissedLiquidations);
    }

    private String buildFuturesUrl() {
        String streams = props.getSymbols().stream()
                .map(s -> s.toLowerCase() + "@markPrice@1s")
                .reduce((a, b) -> a + "/" + b).orElse("");
        String base = props.getFuturesWsUrl();
        if (props.getSymbols().size() == 1) {
            return base + "/" + streams;
        }
        return base.replace("/ws", "/stream?streams=" + streams);
    }

    private void onFuturesMessage(String raw) {
        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = extractQuoted(raw, sIdx + 5);

        int pIdx = raw.indexOf("\"p\":\"");
        if (pIdx < 0) return;
        String markPrice = extractQuoted(raw, pIdx + 5);

        redisTemplate.opsForValue().set(REDIS_MARK_PRICE_KEY_PREFIX + symbol, markPrice);

        String spotPrice = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + symbol);
        BigDecimal cp = spotPrice != null ? new BigDecimal(spotPrice) : new BigDecimal(markPrice);
        Thread.startVirtualThread(() -> {
            try { futuresLiquidationService.checkOnPriceUpdate(symbol, new BigDecimal(markPrice), cp); }
            catch (Exception e) { log.warn("futures强平检查异常 {}: {}", symbol, e.getMessage()); }
        });
    }

    // ── REST兜底：WS断开期间切REST轮询保证价格不中断 ──

    private void startFallbackPolling() {
        if (fallbackTask != null && !fallbackTask.isCancelled()) return;
        long interval = props.getFallbackPollInterval();
        fallbackTask = scheduler.scheduleAtFixedRate(() -> {
            for (String symbol : props.getSymbols()) {
                try {
                    String json = restClient.getTickerPrice(symbol);
                    updatePriceFromJson(symbol, json);
                } catch (Exception e) {
                    log.warn("REST轮询{}失败: {}", symbol, e.getMessage());
                }
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
        log.info("启动REST轮询兜底，间隔{}ms", interval);
    }

    private void stopFallbackPolling() {
        if (fallbackTask != null) {
            fallbackTask.cancel(false);
            fallbackTask = null;
            log.info("停止REST轮询兜底");
        }
    }

    private void startFuturesFallbackPolling() {
        if (futuresFallbackTask != null && !futuresFallbackTask.isCancelled()) return;
        long interval = props.getFallbackPollInterval();
        futuresFallbackTask = scheduler.scheduleAtFixedRate(() -> {
            for (String symbol : props.getSymbols()) {
                try {
                    String json = restClient.getMarkPrice(symbol);
                    if (json == null) continue;
                    int idx = json.indexOf("\"markPrice\":\"");
                    if (idx < 0) continue;
                    String markPrice = extractQuoted(json, idx + 13);
                    onFuturesMessage("{\"s\":\"" + symbol + "\",\"p\":\"" + markPrice + "\"}");
                } catch (Exception e) {
                    log.warn("REST轮询Futures {}失败: {}", symbol, e.getMessage());
                }
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
        log.info("启动Futures REST轮询兜底，间隔{}ms", interval);
    }

    private void stopFuturesFallbackPolling() {
        if (futuresFallbackTask != null) {
            futuresFallbackTask.cancel(false);
            futuresFallbackTask = null;
            log.info("停止Futures REST轮询兜底");
        }
    }

    private void updatePriceFromJson(String symbol, String json) {
        int idx = json.indexOf("\"price\":\"");
        if (idx < 0) return;
        String price = extractQuoted(json, idx + 9);
        onSpotMessage("{\"s\":\"" + symbol + "\",\"c\":\"" + price + "\"}");
    }

    // ── 恢复 ──

    private void recoverMissedLimitOrders() {
        try {
            for (String symbol : props.getSymbols()) {
                BigDecimal[] lowHigh = restClient.getRecentHighLow(symbol);
                if (lowHigh != null) {
                    cryptoOrderService.recoverLimitOrders(symbol, lowHigh[0], lowHigh[1]);
                }
                BigDecimal[] markLowHigh = restClient.getRecentMarkPriceHighLow(symbol);
                if (markLowHigh != null) {
                    futuresService.recoverLimitOrders(symbol, markLowHigh[0], markLowHigh[1]);
                }
            }
        } catch (Exception e) {
            log.error("恢复限价单失败", e);
        }
    }

    private void recoverMissedLiquidations() {
        try {
            for (String symbol : props.getSymbols()) {
                BigDecimal[] lowHigh = restClient.getRecentMarkPriceHighLow(symbol);
                BigDecimal[] spotLowHigh = restClient.getRecentHighLow(symbol);
                if (lowHigh != null) {
                    BigDecimal spotLow = spotLowHigh != null ? spotLowHigh[0] : lowHigh[0];
                    BigDecimal spotHigh = spotLowHigh != null ? spotLowHigh[1] : lowHigh[1];
                    futuresLiquidationService.checkOnPriceUpdate(symbol, lowHigh[0], spotLow);
                    futuresLiquidationService.checkOnPriceUpdate(symbol, lowHigh[1], spotHigh);
                }
            }
        } catch (Exception e) {
            log.error("恢复强平检查失败", e);
        }
    }

    // ── 工具 ──

    private static String extractQuoted(String raw, int start) {
        int end = raw.indexOf('"', start);
        return raw.substring(start, end);
    }

    // ── 通用WS连接 ──

    private class WsConnection {
        private final String name;
        private final Supplier<String> urlBuilder;
        private final Consumer<String> messageHandler;
        private final Runnable onConnected;

        final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        private final AtomicBoolean reconnecting = new AtomicBoolean(false);
        private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

        WsConnection(String name, Supplier<String> urlBuilder, Consumer<String> messageHandler, Runnable onConnected) {
            this.name = name;
            this.urlBuilder = urlBuilder;
            this.messageHandler = messageHandler;
            this.onConnected = onConnected;
        }

        void connect() {
            if (shutdown.get()) return;
            String url = urlBuilder.get();
            log.info("连接Binance {} WS: {}", name, url);

            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(url), new Listener())
                    .thenAccept(ws -> {
                        wsRef.set(ws);
                        connected.set(true);
                        reconnecting.set(false);
                        reconnectAttempt.set(0);
                        log.info("Binance {} WS已连接", name);
                        if (onConnected != null) onConnected.run();
                    })
                    .exceptionally(ex -> {
                        log.error("Binance {} WS连接失败: {}", name, ex.getMessage());
                        reconnecting.set(false);
                        scheduleReconnect();
                        return null;
                    });
        }

        // CAS保证并发场景只触发一次重连
        void scheduleReconnect() {
            if (shutdown.get()) return;
            if (!reconnecting.compareAndSet(false, true)) return;
            connected.set(false);
            // Spot断开时自动启动REST轮询兜底
            if ("Spot".equals(name)) startFallbackPolling();
            // Futures断开时自动启动REST轮询mark price兜底
            if ("Futures".equals(name)) startFuturesFallbackPolling();
            // 指数退避：1s → 2s → 5s → 10s → 30s封顶
            int attempt = reconnectAttempt.getAndIncrement();
            int delay = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
            log.info("{}秒后重连Binance {} WS（第{}次）", delay, name, attempt + 1);
            scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
        }

        void close() {
            WebSocket ws = wsRef.get();
            if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }

        private class Listener implements WebSocket.Listener {
            // 缓冲区拼接WebSocket帧分片，last=true时为完整消息
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                log.info("Binance {} WS onOpen", name);
                // 背压控制：每次只请求1条消息，处理完再拉下一条
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    try { messageHandler.accept(buffer.toString()); }
                    catch (Exception e) { log.warn("解析Binance {} WS消息失败: {}", name, e.getMessage()); }
                    buffer.setLength(0);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                webSocket.sendPong(message);
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                log.warn("Binance {} WS关闭: code={} reason={}", name, statusCode, reason);
                WsConnection.this.scheduleReconnect();
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                log.error("Binance {} WS错误: {}", name, error.getMessage());
                WsConnection.this.scheduleReconnect();
            }
        }
    }
}
