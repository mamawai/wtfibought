package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibfeed.WsConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.http.WebSocket;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 现货 miniTicker 流（重 handler）：解析最新价写 Redis/缓存/广播 + 发撮合事件；
 * 断线时启 REST 轮询兜底，重连后用 REST 拉区间高低价补漏离线期错过的限价单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpotStreamHandler implements StreamHandler {

    private static final String REDIS_KEY_PREFIX = "market:price:";

    private final BinanceProperties props;
    private final StringRedisTemplate redisTemplate;
    private final CacheService cacheService;
    private final MarketBroadcaster broadcastService;
    private final BinanceRestClient restClient;
    private final MatchPricePublisher matchPricePublisher;

    private WsConnection conn;
    private RestFallbackPoller fallback;

    @Override public String name() { return "Spot"; }
    @Override public long maxIdleSeconds() { return 90; }

    @Override
    public void bind(WsConnection conn, ScheduledExecutorService scheduler) {
        this.conn = conn;
        this.fallback = new RestFallbackPoller(scheduler, props.getFallbackPollInterval(), "", this::pollOnce);
    }

    private boolean isConnected() {
        return conn != null && conn.isConnected();
    }

    @Override
    public String buildUrl() {
        // 现货端点结构与期货不同：无 /market /public 段，单流直接 base/{stream}，多流走 /stream?streams=
        String streams = StreamUrls.joinStreams(props.getSymbols(), "miniTicker");
        if (props.getSymbols().size() == 1) {
            return props.getWsUrl() + "/" + streams;
        }
        return props.getWsUrl().replace("/ws", "/stream?streams=" + streams);
    }

    @Override
    public void onConnected(WebSocket ws) {
        fallback.stop();
        // 重连后用REST拉最近高低价，补漏离线期间错过的限价单
        Thread.startVirtualThread(this::recoverMissedLimitOrders);
    }

    @Override
    public void onDisconnected() {
        fallback.start();
    }

    @Override
    public void onMessage(String raw) {
        // 手动indexOf解析避免Jackson反序列化开销
        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = StreamParse.extractQuoted(raw, sIdx + 5);

        int cIdx = raw.indexOf("\"c\":\"");
        if (cIdx < 0) return;
        String price = StreamParse.extractQuoted(raw, cIdx + 5);

        // 提取服务端时间戳，缺失/异常则取本地时间
        int eIdx = raw.indexOf("\"E\":");
        long ts = StreamParse.getEventTime(raw, eIdx, System.currentTimeMillis());

        redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + symbol, price);
        BigDecimal bd = new BigDecimal(price);
        cacheService.putCryptoPrice(symbol, bd);
        String msg = "{\"price\":\"" + price + "\",\"ts\":" + ts
                + ",\"ws\":" + isConnected() + "}";
        broadcastService.broadcastCryptoQuote(symbol, msg);

        // 价格事件发 Redis，sim 侧 MatchPriceConsumer 订阅后触发现货限价单撮合（解耦：不再进程内直调）
        matchPricePublisher.publish("{\"symbol\":\"" + symbol + "\",\"type\":\"spot\",\"price\":\"" + price + "\"}");
    }

    // ── REST兜底：WS断开期间切REST轮询保证价格不中断 ──

    private void pollOnce() {
        for (String symbol : props.getSymbols()) {
            try {
                String json = restClient.getTickerPrice(symbol);
                updatePriceFromJson(symbol, json);
            } catch (Exception e) {
                log.warn("REST轮询{}失败: {}", symbol, e.getMessage());
            }
        }
    }

    private void updatePriceFromJson(String symbol, String json) {
        int idx = json.indexOf("\"price\":\"");
        if (idx < 0) return;
        String price = StreamParse.extractQuoted(json, idx + 9);
        onMessage("{\"s\":\"" + symbol + "\",\"c\":\"" + price + "\"}");
    }

    private void recoverMissedLimitOrders() {
        try {
            for (String symbol : props.getSymbols()) {
                BigDecimal[] lowHigh = restClient.getRecentHighLow(symbol);
                if (lowHigh != null) {
                    // 发恢复事件，sim 侧按区间高低价补触发限价单（解耦：撮合不在 feed）
                    matchPricePublisher.publish("{\"symbol\":\"" + symbol + "\",\"type\":\"spot-recover\",\"low\":\""
                            + lowHigh[0].toPlainString() + "\",\"high\":\"" + lowHigh[1].toPlainString() + "\"}");
                }
            }
        } catch (Exception e) {
            log.error("恢复现货限价单失败", e);
        }
    }
}
