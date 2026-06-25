package com.mawai.wiibfeed.stream;

import java.util.List;

/** Binance WS 订阅 URL 拼装：消除各 StreamHandler buildUrl 的重复模板。 */
final class StreamUrls {

    private StreamUrls() {}

    /** 把 symbols 拼成 "btcusdt@suffix/ethusdt@suffix" 组合流字符串 */
    static String joinStreams(List<String> symbols, String suffix) {
        return symbols.stream()
                .map(s -> s.toLowerCase() + "@" + suffix)
                .reduce((a, b) -> a + "/" + b).orElse("");
    }

    /**
     * 期货 /market、/public 端点标准 URL：单 symbol 走 /{endpoint}/ws/{stream}，多 symbol 走组合流。
     * 用于 forceOrder/aggTrade/kline(market) 与 depth(public)。
     */
    static String endpointUrl(String baseWsUrl, String endpoint, List<String> symbols, String suffix) {
        String streams = joinStreams(symbols, suffix);
        if (symbols.size() == 1) {
            return baseWsUrl.replace("/ws", "/" + endpoint + "/ws/" + streams);
        }
        return baseWsUrl.replace("/ws", "/" + endpoint + "/stream?streams=" + streams);
    }

    /**
     * 期货端点组合流 URL：单/多 symbol 都走 /{endpoint}/stream?streams=。
     * 用于 markPrice/miniTicker——这两条流不做单流优化（与历史端点行为一致）。
     */
    static String combinedUrl(String baseWsUrl, String endpoint, List<String> symbols, String suffix) {
        return baseWsUrl.replace("/ws", "/" + endpoint + "/stream?streams=" + joinStreams(symbols, suffix));
    }
}
