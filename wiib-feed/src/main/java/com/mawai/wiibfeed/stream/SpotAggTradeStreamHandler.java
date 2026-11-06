package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import com.mawai.wiibfeed.WsConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.http.WebSocket;

/**
 * 现货逐笔成交连接（轻壳）：@aggTrade 走现货端点，覆盖全部现货（含 bStock）。
 * 解析复用 {@link AggTradeStreamHandler#handle}，写现货那条成交 Stream（sim 判现货限价单）；
 * 断过的空窗发 gap（kind=spot），sim 拉现货 1m K 线补。
 */
@Component
@RequiredArgsConstructor
public class SpotAggTradeStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final AggTradeStreamHandler aggTradeHandler;
    private final StringRedisTemplate redisTemplate;
    private final MatchPricePublisher matchPricePublisher;

    private WsConnection conn;
    private GapTracker tracker;

    @Override public String name() { return "SpotAggTrade"; }
    @Override public long maxIdleSeconds() { return 60; }

    @Override
    public void bind(WsConnection conn) {
        this.conn = conn;
        this.tracker = new GapTracker(name(), "spot", redisTemplate, matchPricePublisher);
    }

    @Override
    public String buildUrl() {
        return StreamUrls.spotCombinedUrl(props.getWsUrl(), props.getAllSpotSymbols(), "aggTrade");
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
        aggTradeHandler.handle(raw, MarketStreamChannels.SPOT_TRADES_PREFIX, receivedAt);
    }
}
