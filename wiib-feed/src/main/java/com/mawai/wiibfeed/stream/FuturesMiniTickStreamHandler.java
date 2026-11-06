package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibfeed.WsConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 合约 miniTicker 副连接：Binance 2026-04-23 端点拆分后 miniTicker 单独走 /market，
 * 与 markPrice 分两条物理连接。解析逻辑复用 {@link FuturesStreamHandler#handle}，本类只提供独立 URL。
 */
@Component
@RequiredArgsConstructor
public class FuturesMiniTickStreamHandler implements StreamHandler {

    private final BinanceProperties props;
    private final FuturesStreamHandler futuresHandler;

    @Override public String name() { return "FuturesMiniTick"; }
    @Override public long maxIdleSeconds() { return 90; }

    @Override
    public void bind(WsConnection conn) {
        // 主 handler 判 fws 要看两条连接，把本条交过去
        futuresHandler.setMiniTickConn(conn);
    }

    // Binance 2026-04-23起: miniTicker走/market端点
    @Override
    public String buildUrl() {
        return StreamUrls.combinedUrl(props.getFuturesWsUrl(), "market", props.getAllFuturesSymbols(), "miniTicker");
    }

    @Override
    public void onMessage(String raw) {
        futuresHandler.handle(raw);
    }

    @Override
    public void onDisconnected() {
        futuresHandler.broadcastFuturesDisconnected();
    }
}
