package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 纯合约标的（大宗商品金/油 + TradFi 美股/ETF 永续）5m K线连接（轻壳）：@kline_5m，走合约 /market 端点。
 * 解析复用 {@link KlineStreamHandler#handle} 并传 feedStrategy=false——只广播给前端蜡烛图，
 * 绝不进策略 Stream（策略只吃 crypto 5m，混入会污染预测/触发）。
 * 两组符号都未配时 buildUrl 返回空串，BinanceWsClient 据此跳过不建连。
 */
@Component
@RequiredArgsConstructor
public class CommodityKline5mStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final KlineStreamHandler klineHandler;

    @Override public String name() { return "CommodityKline5m"; }
    @Override public long maxIdleSeconds() { return 360; }

    @Override
    public String buildUrl() {
        List<String> syms = props.getFuturesOnlySymbols();
        if (syms.isEmpty()) return "";   // 无纯合约标的则不建连
        return StreamUrls.endpointUrl(props.getFuturesWsUrl(), "market", syms, "kline_5m");
    }

    @Override
    public void onMessage(String raw) {
        klineHandler.handle(raw, false);   // 只广播，不喂策略
    }
}
