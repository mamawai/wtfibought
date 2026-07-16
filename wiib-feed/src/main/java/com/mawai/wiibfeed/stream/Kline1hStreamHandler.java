package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 1h K线副连接（轻壳）：@kline_1h。仅广播给前端蜡烛图(1h 周期视图)，解析复用 {@link KlineStreamHandler#handle}
 * 并传 feedStrategy=false——与 15m 同理，绝不进策略 Stream（策略只吃 5m）。属 /market 端点。
 */
@Component
@RequiredArgsConstructor
public class Kline1hStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final KlineStreamHandler klineHandler;

    @Override public String name() { return "Kline1h"; }
    @Override public long maxIdleSeconds() { return 360; }

    @Override
    public String buildUrl() {
        return StreamUrls.endpointUrl(props.getFuturesWsUrl(), "market", props.getSymbols(), "kline_1h");
    }

    @Override
    public void onMessage(String raw) {
        klineHandler.handle(raw, false);   // 只广播，不喂策略
    }
}
