package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibfeed.WsConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 深度快照流：@depth20@100ms 完整 top20 快照 → DepthStreamCache，时刻记这一帧收到的时候。属 /public 端点。 */
@Component
@RequiredArgsConstructor
public class DepthStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final DepthStreamCache depthStreamCache;

    private WsConnection conn;

    @Override public String name() { return "Depth"; }
    @Override public long maxIdleSeconds() { return 60; }

    @Override
    public void bind(WsConnection conn) {
        this.conn = conn;
    }

    @Override
    public String buildUrl() {
        return StreamUrls.endpointUrl(props.getFuturesWsUrl(), "public", props.getAllFuturesSymbols(), "depth20@100ms");
    }

    @Override
    public void onMessage(String raw) {
        // depth20@100ms 推送完整 top20 快照，格式和 REST /fapi/v1/depth 一致（JSON 里带交易所事件时间 E）
        // 组合流: {"stream":"btcusdt@depth20@100ms","data":{"e":"depthUpdate","E":...,"s":"BTCUSDT","b":[...],"a":[...]}}
        // 单流:   {"e":"depthUpdate","E":...,"s":"BTCUSDT","b":[...],"a":[...]}
        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = StreamParse.extractQuoted(raw, sIdx + 5);

        // 转换字段名: WS用 "b"/"a"，REST用 "bids"/"asks"，统一为 REST 格式
        String depthJson = StreamParse.dataObject(raw).replace("\"b\":", "\"bids\":").replace("\"a\":", "\"asks\":");

        depthStreamCache.onDepthUpdate(symbol, depthJson, conn.frameReceivedAt());
    }
}
