package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import com.mawai.wiibfeed.WsConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.http.WebSocket;

/**
 * 合约逐笔成交流：@aggTrade → 合约成交 Stream（sim 判限价单、止损、止盈，agent 算订单流指标）。属 /market 端点。
 * 每帧记最后一帧时间，连上时发 gap（kind=futures），sim 拉合约 1m K 线补这段没收到的成交。
 */
@Component
@RequiredArgsConstructor
public class AggTradeStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final TradeStreamWriter writer;
    private final StringRedisTemplate redisTemplate;
    private final MatchPricePublisher matchPricePublisher;

    private WsConnection conn;
    private GapTracker tracker;

    @Override public String name() { return "AggTrade"; }
    @Override public long maxIdleSeconds() { return 60; }

    @Override
    public void bind(WsConnection conn) {
        this.conn = conn;
        this.tracker = new GapTracker(name(), "futures", redisTemplate, matchPricePublisher);
    }

    @Override
    public String buildUrl() {
        return StreamUrls.endpointUrl(props.getFuturesWsUrl(), "market", props.getAllFuturesSymbols(), "aggTrade");
    }

    @Override
    public void onConnected(WebSocket ws) {
        tracker.publishGap();
    }

    /** 挤掉的是一笔笔成交，丢了就没了：按被挤掉的那段发空窗补漏 */
    @Override
    public void onDropped(long fromMs, long toMs) {
        tracker.publishGap(fromMs, toMs);
    }

    @Override
    public void onMessage(String raw) {
        long receivedAt = conn.frameReceivedAt();
        tracker.touch(receivedAt);
        handle(raw, MarketStreamChannels.FUTURES_TRADES_PREFIX, receivedAt);
    }

    /**
     * 解析一条 aggTrade，写进 keyPrefix 那条 Stream；价、量原样存字符串，receivedAt 是这一帧收到的时刻。
     * 合约、现货格式一样，现货副连接 {@link SpotAggTradeStreamHandler} 复用本方法。
     * 组合流 {"stream":"btcusdt@aggTrade","data":{...}}；单流就是 data 本身：
     * {"e":"aggTrade","E":...,"s":"BTCUSDT","a":...,"p":"...","q":"...","f":...,"l":...,"T":...,"m":true}
     */
    public void handle(String raw, String keyPrefix, long receivedAt) {
        int sIdx = raw.indexOf("\"s\":\"");
        int pIdx = raw.indexOf("\"p\":\"");
        int qIdx = raw.indexOf("\"q\":\"");
        if (sIdx < 0 || pIdx < 0 || qIdx < 0) return;
        // "m":true/false —— 买方是挂单方，即这笔是主动卖
        int mIdx = raw.indexOf("\"m\":", qIdx);
        if (mIdx < 0) return;

        long ts = StreamParse.getEventTime(raw, raw.indexOf("\"T\":", sIdx), System.currentTimeMillis());
        writer.append(keyPrefix, StreamParse.extractQuoted(raw, sIdx + 5), StreamParse.extractQuoted(raw, pIdx + 5),
                StreamParse.extractQuoted(raw, qIdx + 5), raw.charAt(mIdx + 4) == 't', ts, receivedAt);
    }
}
