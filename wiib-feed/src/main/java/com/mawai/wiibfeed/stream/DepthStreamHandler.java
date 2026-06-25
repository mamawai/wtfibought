package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.DepthStreamCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 深度快照流：@depth20@100ms 完整 top20 快照 → DepthStreamCache。属 /public 端点。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepthStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final DepthStreamCache depthStreamCache;

    @Override public String name() { return "Depth"; }
    @Override public long maxIdleSeconds() { return 60; }

    @Override
    public String buildUrl() {
        return StreamUrls.endpointUrl(props.getFuturesWsUrl(), "public", props.getSymbols(), "depth20@100ms");
    }

    @Override
    public void onMessage(String raw) {
        // depth20@100ms 推送完整 top20 快照，格式和 REST /fapi/v1/depth 一致
        // 组合流: {"stream":"btcusdt@depth20@100ms","data":{"e":"depthUpdate","E":...,"s":"BTCUSDT","b":[...],"a":[...]}}
        // 单流:   {"e":"depthUpdate","E":...,"s":"BTCUSDT","b":[...],"a":[...]}
        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = StreamParse.extractQuoted(raw, sIdx + 5);

        // 提取 data 部分（组合流）或整体（单流）
        int dataIdx = raw.indexOf("\"data\":");
        String depthJson;
        if (dataIdx >= 0) {
            int dataStart = dataIdx + 7;
            // data 对象一直到配平的 }
            int depth = 0;
            int end = dataStart;
            for (int i = dataStart; i < raw.length(); i++) {
                if (raw.charAt(i) == '{') depth++;
                else if (raw.charAt(i) == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
            }
            depthJson = raw.substring(dataStart, end);
        } else {
            depthJson = raw;
        }

        // 解析交易所事件时间 E，用于 freshness 判定
        int eIdx = depthJson.indexOf("\"E\":");
        long eventTime = StreamParse.getEventTime(depthJson, eIdx, System.currentTimeMillis());

        // 转换字段名: WS用 "b"/"a"，REST用 "bids"/"asks"，统一为 REST 格式
        depthJson = depthJson.replace("\"b\":", "\"bids\":").replace("\"a\":", "\"asks\":");

        depthStreamCache.onDepthUpdate(symbol, depthJson, eventTime);
    }
}
