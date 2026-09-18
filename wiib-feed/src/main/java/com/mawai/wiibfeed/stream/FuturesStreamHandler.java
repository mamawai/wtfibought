package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibfeed.WsConnection;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.http.WebSocket;

/**
 * 合约流（重 handler）：markPrice@1s 走本 handler 主连接，miniTicker 走 FuturesMiniTick 副连接，
 * 两者共用 {@link #handle} 解析（靠 stream 名区分 markPrice vs miniTicker），各自记自己的空窗。
 * <p>Binance 2026-04-23 起期货 WS 端点拆分，markPrice 与 miniTicker 必须分两条物理连接，故有副连接。
 */
@Component
@RequiredArgsConstructor
public class FuturesStreamHandler implements StreamHandler {

    private static final String REDIS_MARK_PRICE_KEY_PREFIX = "market:markprice:";
    private static final String REDIS_FUTURES_PRICE_KEY_PREFIX = "market:futures-price:";

    private final BinanceProperties props;
    private final StringRedisTemplate redisTemplate;
    private final CacheService cacheService;
    private final MarketBroadcaster broadcastService;
    private final MatchPricePublisher matchPricePublisher;

    private WsConnection conn;
    /** 副连接（miniTicker），bind 时由副 handler 交进来，只用于判 fws */
    @Setter
    private WsConnection miniTickConn;
    private GapTracker tracker;

    @Override public String name() { return "Futures"; }
    @Override public long maxIdleSeconds() { return 90; }

    @Override
    public void bind(WsConnection conn) {
        this.conn = conn;
        this.tracker = new GapTracker(name(), "futures", redisTemplate, matchPricePublisher);
    }

    /** 两条合约连接都连上才算合约行情正常，缺一条前端就该看到断线 */
    private boolean isFuturesConnected() {
        return conn != null && conn.isConnected() && miniTickConn != null && miniTickConn.isConnected();
    }

    // Binance 2026-04-23起: markPrice走/market端点
    @Override
    public String buildUrl() {
        return StreamUrls.combinedUrl(props.getFuturesWsUrl(), "market", props.getAllFuturesSymbols(), "markPrice@1s");
    }

    @Override
    public void onConnected(WebSocket ws) {
        tracker.publishGap();
    }

    @Override
    public void onDisconnected() {
        broadcastFuturesDisconnected();
    }

    /** 断线帧只带 fws：前端合约分支没 fp/mp 会沿用上一价。两条连接共用 */
    void broadcastFuturesDisconnected() {
        for (String symbol : props.getAllFuturesSymbols()) {
            broadcastService.broadcastFuturesQuote(symbol, "{\"fws\":false}");
        }
    }

    @Override
    public void onMessage(String raw) {
        tracker.touch();
        handle(raw);
    }

    /** 两条合约连接共用的解析体 */
    void handle(String raw) {
        // 组合流: {"stream":"btcusdt@markPrice@1s","data":{...,"s":"BTCUSDT","p":"..."}} / {"stream":"btcusdt@miniTicker","data":{...,"s":"BTCUSDT","c":"..."}}
        int streamIdx = raw.indexOf("\"stream\":\"");
        if (streamIdx < 0) return;
        int streamStart = streamIdx + 10;
        int streamEnd = raw.indexOf('"', streamStart);
        if (streamEnd < 0) return;
        boolean isMarkPrice = raw.regionMatches(streamEnd - 12, "markPrice@1s", 0, 12);

        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = StreamParse.extractQuoted(raw, sIdx + 5);

        if (isMarkPrice) {
            int pIdx = raw.indexOf("\"p\":\"", sIdx);
            if (pIdx < 0) return;
            String markPrice = StreamParse.extractQuoted(raw, pIdx + 5);
            redisTemplate.opsForValue().set(REDIS_MARK_PRICE_KEY_PREFIX + symbol, markPrice);
            cacheService.putMarkPrice(symbol, new BigDecimal(markPrice));

            broadcastService.broadcastFuturesQuote(symbol, "{\"mp\":\"" + markPrice + "\",\"fws\":" + isFuturesConnected() + "}");

            // markPrice 事件发 Redis：sim 侧消费做强平、quant 侧消费喂哨兵（解耦：currentPrice 由 sim 自己读 KV）
            matchPricePublisher.publish("{\"symbol\":\"" + symbol + "\",\"type\":\"markprice\",\"price\":\"" + markPrice + "\"}");
        } else {
            // miniTicker: "c" 是最新价
            int cIdx = raw.indexOf("\"c\":\"", sIdx);
            if (cIdx < 0) return;
            String price = StreamParse.extractQuoted(raw, cIdx + 5);
            redisTemplate.opsForValue().set(REDIS_FUTURES_PRICE_KEY_PREFIX + symbol, price);
            cacheService.putFuturesPrice(symbol, new BigDecimal(price));

            broadcastService.broadcastFuturesQuote(symbol, "{\"fp\":\"" + price + "\",\"fws\":" + isFuturesConnected() + "}");

            // futures 价格事件发 Redis：sim 侧消费做合约限价单结算（解耦：不再进程内直调）
            matchPricePublisher.publish("{\"symbol\":\"" + symbol + "\",\"type\":\"futures\",\"price\":\"" + price + "\"}");
        }
    }
}
