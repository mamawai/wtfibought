package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibfeed.WsConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.http.WebSocket;

/**
 * 现货 miniTicker 流（重 handler）：解析最新价写 Redis/缓存/广播 + 发撮合事件；
 * 每帧记最后一帧时间，连上时发空窗事件让 sim 补漏，断开时给前端广播断线帧。
 */
@Component
@RequiredArgsConstructor
public class SpotStreamHandler implements StreamHandler {

    private static final String REDIS_KEY_PREFIX = "market:price:";

    private final BinanceProperties props;
    private final StringRedisTemplate redisTemplate;
    private final CacheService cacheService;
    private final MarketBroadcaster broadcastService;
    private final MatchPricePublisher matchPricePublisher;

    private WsConnection conn;
    private GapTracker tracker;

    @Override public String name() { return "Spot"; }
    @Override public long maxIdleSeconds() { return 90; }

    @Override
    public void bind(WsConnection conn) {
        this.conn = conn;
        this.tracker = new GapTracker(name(), "spot", redisTemplate, matchPricePublisher);
    }

    private boolean isConnected() {
        return conn != null && conn.isConnected();
    }

    @Override
    public String buildUrl() {
        // 现货端点结构与期货不同：无 /market /public 段，单流直接 base/{stream}，多流走 /stream?streams=
        String streams = StreamUrls.joinStreams(props.getAllSpotSymbols(), "miniTicker");
        if (props.getAllSpotSymbols().size() == 1) {
            return props.getWsUrl() + "/" + streams;
        }
        return props.getWsUrl().replace("/ws", "/stream?streams=" + streams);
    }

    @Override
    public void onConnected(WebSocket ws) {
        tracker.publishGap();
    }

    /** 断线帧带缓存价（前端现货分支要 price 字段），价缺就跳过该 symbol */
    @Override
    public void onDisconnected() {
        long ts = System.currentTimeMillis();
        for (String symbol : props.getAllSpotSymbols()) {
            BigDecimal price = cacheService.getCryptoPrice(symbol);
            if (price == null) continue;
            broadcastService.broadcastCryptoQuote(symbol,
                    "{\"price\":\"" + price.toPlainString() + "\",\"ts\":" + ts + ",\"ws\":false}");
        }
    }

    @Override
    public void onMessage(String raw) {
        tracker.touch();
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
}
