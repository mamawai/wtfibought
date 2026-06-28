package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 15m K线副连接（轻壳）：@kline_15m。仅广播给前端蜡烛图(7天视图)，解析复用 {@link KlineStreamHandler#handle}
 * 并传 feedStrategy=false——15m 绝不进策略 Stream（策略只吃 5m，混入会污染预测/触发）。属 /market 端点。
 */
@Component
@RequiredArgsConstructor
public class Kline15mStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final KlineStreamHandler klineHandler;

    @Override public String name() { return "Kline15m"; }
    @Override public long maxIdleSeconds() { return 360; }

    @Override
    public String buildUrl() {
        return StreamUrls.endpointUrl(props.getFuturesWsUrl(), "market", props.getSymbols(), "kline_15m");
    }

    @Override
    public void onMessage(String raw) {
        klineHandler.handle(raw, false);   // 只广播，不喂策略
    }
}
