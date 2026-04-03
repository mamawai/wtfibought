package com.mawai.wiibservice.config;

import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.CryptoOrderService;
import com.mawai.wiibservice.service.ForceOrderService;
import com.mawai.wiibservice.service.FuturesLiquidationService;
import com.mawai.wiibservice.service.FuturesSettlementService;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceWsClient implements SmartLifecycle {

    private final BinanceProperties props;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageBroadcastService broadcastService;
    private final BinanceRestClient restClient;
    private final CryptoOrderService cryptoOrderService;
    private final FuturesLiquidationService futuresLiquidationService;
    private final FuturesSettlementService futuresSettlementService;
    private final CacheService cacheService;
    private final ForceOrderService forceOrderService;

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> fallbackTask;
    private ScheduledFuture<?> futuresFallbackTask;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private WsConnection spotWs;
    private WsConnection futuresWs;
    private WsConnection forceOrderWs;

    private static final String REDIS_KEY_PREFIX = "market:price:";
    private static final String REDIS_MARK_PRICE_KEY_PREFIX = "market:markprice:";
    private static final String REDIS_FUTURES_PRICE_KEY_PREFIX = "market:futures-price:";

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

        spotWs = new WsConnection("Spot", this::buildSpotUrl, this::onSpotMessage,
                ws -> onSpotConnected(), this::startFallbackPolling,
                httpClient, scheduler, shutdown);
        futuresWs = new WsConnection("Futures", this::buildFuturesUrl, this::onFuturesMessage,
                ws -> onFuturesConnected(), this::startFuturesFallbackPolling,
                httpClient, scheduler, shutdown);
        forceOrderWs = new WsConnection("ForceOrder", this::buildForceOrderUrl, this::onForceOrderMessage,
                ws -> log.info("ForceOrder WS已连接"), () -> {},
                httpClient, scheduler, shutdown);

        // 启动ws
        spotWs.connect();
        futuresWs.connect();
        forceOrderWs.connect();
    }

    @Override
    public void stop() {
        shutdown.set(true);
        stopFallbackPolling();
        stopFuturesFallbackPolling();
        if (spotWs != null) spotWs.close();
        if (futuresWs != null) futuresWs.close();
        if (forceOrderWs != null) forceOrderWs.close();
        if (scheduler != null) scheduler.shutdownNow();
        if (httpClient != null) httpClient.close();
    }

    @Override public boolean isRunning() { return !shutdown.get() && scheduler != null; }
    @Override public int getPhase() { return 1; }
    @Override public void start() { /* init via @PostConstruct */ }

    public boolean isConnected() {
        return spotWs != null && spotWs.isConnected();
    }

    public boolean isFuturesConnected() {
        return futuresWs != null && futuresWs.isConnected();
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
        BigDecimal bd = new BigDecimal(price);
        cacheService.putCryptoPrice(symbol, bd);
        String markPrice = redisTemplate.opsForValue().get(REDIS_MARK_PRICE_KEY_PREFIX + symbol);
        String msg = "{\"price\":\"" + price + "\",\"ts\":" + ts
                + ",\"ws\":" + isConnected() + ",\"fws\":" + isFuturesConnected()
                + (markPrice != null ? ",\"mp\":\"" + markPrice + "\"" : "") + "}";
        broadcastService.broadcastCryptoQuote(symbol, msg);

        // 虚拟线程跑业务逻辑，不阻塞WS接收线程
        Thread.startVirtualThread(() -> {
            try { cryptoOrderService.onPriceUpdate(symbol, bd); }
            catch (Exception e) { log.warn("crypto限价单检查异常 {}: {}", symbol, e.getMessage()); }
        });
    }

    // ── Futures ──

    private void onFuturesConnected() {
        stopFuturesFallbackPolling();
        Thread.startVirtualThread(() -> {
            recoverMissedFuturesLimitOrders();
            recoverMissedLiquidations();
        });
    }

    private String buildFuturesUrl() {
        java.util.List<String> streams = new java.util.ArrayList<>();
        for (String s : props.getSymbols()) {
            String lower = s.toLowerCase();
            streams.add(lower + "@markPrice@1s");
            streams.add(lower + "@miniTicker");
        }
        String joined = String.join("/", streams);
        String base = props.getFuturesWsUrl();
        return base.replace("/ws", "/stream?streams=" + joined);
    }

    private void onFuturesMessage(String raw) {
        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = extractQuoted(raw, sIdx + 5);

        // @markPrice@1s: 含"p":"(标记价格) → 强平/止损检查
        int pIdx = raw.indexOf("\"p\":\"");
        if (pIdx >= 0) {
            String markPrice = extractQuoted(raw, pIdx + 5);
            redisTemplate.opsForValue().set(REDIS_MARK_PRICE_KEY_PREFIX + symbol, markPrice);
            BigDecimal mp = new BigDecimal(markPrice);
            cacheService.putMarkPrice(symbol, mp);

            String futuresPrice = redisTemplate.opsForValue().get(REDIS_FUTURES_PRICE_KEY_PREFIX + symbol);
            BigDecimal cp = futuresPrice != null ? new BigDecimal(futuresPrice) : mp;
            Thread.startVirtualThread(() -> {
                try { futuresLiquidationService.checkOnPriceUpdate(symbol, mp, cp); }
                catch (Exception e) { log.warn("futures强平检查异常 {}: {}", symbol, e.getMessage()); }
            });
            return;
        }

        // @miniTicker: 含"c":"(合约最新价) → 限价单触发
        int cIdx = raw.indexOf("\"c\":\"");
        if (cIdx >= 0) {
            String price = extractQuoted(raw, cIdx + 5);
            redisTemplate.opsForValue().set(REDIS_FUTURES_PRICE_KEY_PREFIX + symbol, price);
            BigDecimal bd = new BigDecimal(price);
            cacheService.putFuturesPrice(symbol, bd);

            Thread.startVirtualThread(() -> {
                try { futuresSettlementService.onPriceUpdate(symbol, bd); }
                catch (Exception e) { log.warn("futures限价单检查异常 {}: {}", symbol, e.getMessage()); }
            });
        }
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
                    if (json != null) {
                        int idx = json.indexOf("\"markPrice\":\"");
                        if (idx >= 0) {
                            String markPrice = extractQuoted(json, idx + 13);
                            onFuturesMessage("{\"s\":\"" + symbol + "\",\"p\":\"" + markPrice + "\"}");
                        }
                    }
                    String tickerJson = restClient.getFutures24hTicker(symbol);
                    if (tickerJson != null) {
                        int idx = tickerJson.indexOf("\"lastPrice\":\"");
                        if (idx >= 0) {
                            String lastPrice = extractQuoted(tickerJson, idx + 13);
                            onFuturesMessage("{\"s\":\"" + symbol + "\",\"c\":\"" + lastPrice + "\"}");
                        }
                    }
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
            }
        } catch (Exception e) {
            log.error("恢复现货限价单失败", e);
        }
    }

    private void recoverMissedFuturesLimitOrders() {
        try {
            for (String symbol : props.getSymbols()) {
                BigDecimal[] futuresLowHigh = restClient.getRecentFuturesHighLow(symbol);
                if (futuresLowHigh != null) {
                    futuresSettlementService.recoverLimitOrders(symbol, futuresLowHigh[0], futuresLowHigh[1]);
                }
            }
        } catch (Exception e) {
            log.error("恢复合约限价单失败", e);
        }
    }

    private void recoverMissedLiquidations() {
        try {
            for (String symbol : props.getSymbols()) {
                BigDecimal[] markLowHigh = restClient.getRecentMarkPriceHighLow(symbol);
                BigDecimal[] futuresLowHigh = restClient.getRecentFuturesHighLow(symbol);
                if (markLowHigh != null) {
                    BigDecimal futuresLow = futuresLowHigh != null ? futuresLowHigh[0] : markLowHigh[0];
                    BigDecimal futuresHigh = futuresLowHigh != null ? futuresLowHigh[1] : markLowHigh[1];
                    futuresLiquidationService.checkOnPriceUpdate(symbol, markLowHigh[0], futuresLow);
                    futuresLiquidationService.checkOnPriceUpdate(symbol, markLowHigh[1], futuresHigh);
                }
            }
        } catch (Exception e) {
            log.error("恢复强平检查失败", e);
        }
    }

    // ── ForceOrder（全市场爆仓） ──

    private String buildForceOrderUrl() {
        return props.getFuturesWsUrl() + "/!forceOrder@arr";
    }

    private void onForceOrderMessage(String raw) {
        // {"e":"forceOrder","E":...,"o":{"s":"BTCUSDT","S":"SELL","p":"66531.20","ap":"66723.50","q":"0.069","X":"FILLED","T":1775182662535}}
        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = extractQuoted(raw, sIdx + 5);

        int sideIdx = raw.indexOf("\"S\":\"");
        if (sideIdx < 0) return;
        String side = extractQuoted(raw, sideIdx + 5);

        int pIdx = raw.indexOf("\"p\":\"");
        if (pIdx < 0) return;
        String price = extractQuoted(raw, pIdx + 5);

        int apIdx = raw.indexOf("\"ap\":\"");
        if (apIdx < 0) return;
        String avgPrice = extractQuoted(raw, apIdx + 6);

        int qIdx = raw.indexOf("\"q\":\"");
        if (qIdx < 0) return;
        String qty = extractQuoted(raw, qIdx + 5);

        int xIdx = raw.indexOf("\"X\":\"");
        String status = xIdx >= 0 ? extractQuoted(raw, xIdx + 5) : "FILLED";

        int tIdx = raw.indexOf("\"T\":");
        long tt = System.currentTimeMillis();
        if (tIdx >= 0) {
            int tStart = tIdx + 4;
            int tEnd = raw.indexOf(',', tStart);
            if (tEnd < 0) tEnd = raw.indexOf('}', tStart);
            tt = Long.parseLong(raw.substring(tStart, tEnd).trim());
        }
        final long tradeTime = tt;

        Thread.startVirtualThread(() -> {
            try {
                forceOrderService.handleForceOrder(symbol, side,
                        new BigDecimal(price), new BigDecimal(avgPrice),
                        new BigDecimal(qty), status, tradeTime);
            } catch (Exception e) {
                log.warn("爆仓入库异常 {} {}: {}", symbol, side, e.getMessage());
            }
        });
    }

    // ── 工具 ──

    private static String extractQuoted(String raw, int start) {
        int end = raw.indexOf('"', start);
        return raw.substring(start, end);
    }

}
