package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibfeed.book.OrderBooks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.http.WebSocket;

/**
 * 增量深度流：@depth@500ms 只推这半秒里变过的档（全价位，量是变化后的总量），
 * 喂给本地盘口攒最优 20 档以外的部分，交易页盘口的大单位靠它。属 /public 端点。
 */
@Component
@RequiredArgsConstructor
public class DepthDiffStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final OrderBooks books;

    @Override public String name() { return "DepthDiff"; }
    @Override public long maxIdleSeconds() { return 60; }

    @Override
    public String buildUrl() {
        return StreamUrls.endpointUrl(props.getFuturesWsUrl(), "public", props.getAllFuturesSymbols(), "depth@500ms");
    }

    @Override
    public void onConnected(WebSocket ws) {
        books.resetDiff();
    }

    @Override
    public void onDisconnected() {
        books.resetDiff();
    }

    @Override
    public void onMessage(String raw) {
        // 组合流: {"stream":"btcusdt@depth@500ms","data":{"e":"depthUpdate","E":...,"T":...,"s":"BTCUSDT","ps":"BTCUSDT",
        //          "U":157,"u":160,"pu":149,"b":[["价","量"],...],"a":[...]}}，单流没有外面那层
        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = StreamParse.extractQuoted(raw, sIdx + 5);
        books.onDiff(symbol, StreamParse.longAfter(raw, "\"pu\":", sIdx), StreamParse.longAfter(raw, "\"u\":", sIdx), raw);
    }
}
