package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibfeed.WsConnection;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.http.WebSocket;

/**
 * 合约流（重 handler）：markPrice@1s 走本 handler 主连接，miniTicker 走 FuturesMiniTick 副连接，
 * 两者共用 {@link #handle} 解析（靠 stream 名区分 markPrice vs miniTicker）。
 * 标记价连接记空窗，连上时发 gap（kind=mark），sim 拉 mark 1m K 线补强平。
 * <p>Binance 2026-04-23 起期货 WS 端点拆分，markPrice 与 miniTicker 必须分两条物理连接，故有副连接。
 */
@Component
@RequiredArgsConstructor
public class FuturesStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final StringRedisTemplate redisTemplate;
    private final CacheService cacheService;
    private final MarketBroadcaster broadcastService;
    private final MatchPricePublisher matchPricePublisher;
    private final FundingRateWriter fundingRateWriter;

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
        this.tracker = new GapTracker(name(), "mark", redisTemplate, matchPricePublisher);
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
        tracker.touch(conn.frameReceivedAt());
        handle(raw);
    }

    /** 一帧里的币、价，和这是不是标记价流（不是就是 miniTicker 的最新价）；标记价帧还带当期费率和下次结算时刻 */
    record Tick(String symbol, String price, boolean markPrice, String rate, long nextTime) {}

    /**
     * 组合流一帧：{"stream":"btcusdt@markPrice@1s","data":{...,"s":"BTCUSDT","p":"...","r":"...","T":...}} /
     * {"stream":"btcusdt@miniTicker","data":{...,"s":"BTCUSDT","c":"..."}}。缺字段回 null。交易员那两条连接也用它。
     */
    static Tick parse(String raw) {
        int streamIdx = raw.indexOf("\"stream\":\"");
        if (streamIdx < 0) return null;
        int streamStart = streamIdx + 10;
        int streamEnd = raw.indexOf('"', streamStart);
        if (streamEnd < 0) return null;
        boolean isMarkPrice = raw.regionMatches(streamEnd - 12, "markPrice@1s", 0, 12);

        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return null;
        String symbol = StreamParse.extractQuoted(raw, sIdx + 5);
        // markPrice 的 "p" 是标记价，miniTicker 的 "c" 是最新价
        int pIdx = raw.indexOf(isMarkPrice ? "\"p\":\"" : "\"c\":\"", sIdx);
        if (pIdx < 0) return null;
        String price = StreamParse.extractQuoted(raw, pIdx + 5);
        if (!isMarkPrice) return new Tick(symbol, price, false, null, 0L);
        // markPrice 帧：r 当期费率，T 下次结算时刻
        return new Tick(symbol, price, true, StreamParse.extractField(raw, "\"r\":\"", sIdx),
                StreamParse.getEventTime(raw, raw.indexOf("\"T\":", sIdx), 0L));
    }

    /** 两条合约连接共用的解析体 */
    void handle(String raw) {
        Tick tick = parse(raw);
        if (tick == null) return;
        String symbol = tick.symbol();
        String price = tick.price();
        if (tick.markPrice()) {
            // 标记价连写入时刻一起落：markPrice@1s 每秒必推，sim 市价开仓按它判这个币的行情停没停
            cacheService.setMarkPrice(symbol, price, System.currentTimeMillis());

            broadcastService.broadcastFuturesQuote(symbol, "{\"mp\":\"" + price + "\",\"fws\":" + isFuturesConnected() + "}");

            // markPrice 事件发 Redis：sim 做强平，agent 喂哨兵
            matchPricePublisher.publish("{\"symbol\":\"" + symbol + "\",\"type\":\"markprice\",\"price\":\"" + price + "\"}");
            fundingRateWriter.write(symbol, tick.rate(), tick.nextTime());
        } else {
            cacheService.setFuturesPrice(symbol, price);

            broadcastService.broadcastFuturesQuote(symbol, "{\"fp\":\"" + price + "\",\"fws\":" + isFuturesConnected() + "}");

            // 最新价 tick 发 Redis：quant 执行层的触价单用（sim 的限价单看成交 Stream）
            matchPricePublisher.publish("{\"symbol\":\"" + symbol + "\",\"type\":\"futures\",\"price\":\"" + price + "\"}");
        }
    }
}
