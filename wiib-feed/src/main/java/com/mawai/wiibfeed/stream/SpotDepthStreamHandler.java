package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibfeed.WsConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 现货深度快照流：@depth20@100ms 前 20 档 → DepthStreamCache 现货那份，覆盖全部现货（含 bStock）。
 * 现货这条推送只有 lastUpdateId/bids/asks，不带币名和事件时间：币名从组合流的 stream 名取，时间记 feed 收到这一帧的时刻。
 */
@Component
@RequiredArgsConstructor
public class SpotDepthStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final DepthStreamCache depthStreamCache;

    private WsConnection conn;

    @Override public String name() { return "SpotDepth"; }
    @Override public long maxIdleSeconds() { return 60; }

    @Override
    public void bind(WsConnection conn) {
        this.conn = conn;
    }

    @Override
    public String buildUrl() {
        // 必须走组合流：单流报文里没有币名
        return StreamUrls.spotCombinedUrl(props.getWsUrl(), props.getAllSpotSymbols(), "depth20@100ms");
    }

    @Override
    public void onMessage(String raw) {
        // {"stream":"btcusdt@depth20@100ms","data":{"lastUpdateId":...,"bids":[["价","量"],...],"asks":[...]}}
        String stream = StreamParse.extractField(raw, "\"stream\":\"", 0);
        String symbol = stream.substring(0, stream.indexOf('@')).toUpperCase(Locale.ROOT);
        depthStreamCache.onSpotDepthUpdate(symbol, StreamParse.dataObject(raw), conn.frameReceivedAt());
    }
}
