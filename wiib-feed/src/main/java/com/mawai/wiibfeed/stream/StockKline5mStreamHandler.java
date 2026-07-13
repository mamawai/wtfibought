package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * bStock 现货 5m K线连接（轻壳）：@kline_5m，走现货端点（stream.binance.com）。
 * bStock 无合约，不能并入走合约端点的 {@link KlineStreamHandler}；解析复用其 handle 并传 feedStrategy=false
 * ——只广播给前端蜡烛图，绝不进策略 Stream（策略只吃 crypto 合约 5m，混入股票会污染预测/触发）。
 * 未配 stockSymbols 时 buildUrl 返回空串，BinanceWsClient 据此跳过不建连。
 */
@Component
@RequiredArgsConstructor
public class StockKline5mStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final KlineStreamHandler klineHandler;

    @Override public String name() { return "StockKline5m"; }
    @Override public long maxIdleSeconds() { return 360; }

    @Override
    public String buildUrl() {
        List<String> syms = props.getStockSymbols();
        if (syms == null || syms.isEmpty()) return "";   // 无 bStock 则不建连
        // 现货端点结构：单流 base/{stream}，多流 /stream?streams=（与 SpotStreamHandler 一致，非合约的 /market 段）
        String streams = StreamUrls.joinStreams(syms, "kline_5m");
        if (syms.size() == 1) {
            return props.getWsUrl() + "/" + streams;
        }
        return props.getWsUrl().replace("/ws", "/stream?streams=" + streams);
    }

    @Override
    public void onMessage(String raw) {
        klineHandler.handle(raw, false);   // 只广播，不喂策略
    }
}
