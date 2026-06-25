package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 逐笔成交流：@aggTrade → OrderFlowAggregator 聚合 order flow 指标。属 /market 端点。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AggTradeStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final OrderFlowAggregator orderFlowAggregator;

    @Override public String name() { return "AggTrade"; }
    @Override public long maxIdleSeconds() { return 60; }

    @Override
    public String buildUrl() {
        return StreamUrls.endpointUrl(props.getFuturesWsUrl(), "market", props.getSymbols(), "aggTrade");
    }

    @Override
    public void onMessage(String raw) {
        // 组合流: {"stream":"btcusdt@aggTrade","data":{...}}
        // 单流:   {"e":"aggTrade","E":...,"s":"BTCUSDT","p":"...","q":"...","m":true/false,...}
        int pIdx = raw.indexOf("\"p\":\"");
        int qIdx = raw.indexOf("\"q\":\"");
        int sIdx = raw.indexOf("\"s\":\"");
        if (pIdx < 0 || qIdx < 0 || sIdx < 0) return;

        String symbol = StreamParse.extractQuoted(raw, sIdx + 5);
        String priceStr = StreamParse.extractQuoted(raw, pIdx + 5);
        String qtyStr = StreamParse.extractQuoted(raw, qIdx + 5);

        // "m":true/false — 手动解析避免JSON开销
        int mIdx = raw.indexOf("\"m\":", qIdx);
        if (mIdx < 0) return;
        boolean isBuyerMaker = raw.charAt(mIdx + 4) == 't';

        // 时间戳
        int tIdx = raw.indexOf("\"T\":", sIdx);
        long ts = StreamParse.getEventTime(raw, tIdx, System.currentTimeMillis());

        try {
            double price = Double.parseDouble(priceStr);
            double qty = Double.parseDouble(qtyStr);
            orderFlowAggregator.onAggTrade(symbol, price, qty, isBuyerMaker, ts);
        } catch (NumberFormatException e) {
            log.warn("[AggTrade] 数值解析失败: p={} q={}", priceStr, qtyStr);
        }

        // 按时间戳低10位概率触发，约千分之二的频率清理过期数据
        if ((ts & 0x3FF) < 2) {
            orderFlowAggregator.trim(symbol);
        }
    }
}
