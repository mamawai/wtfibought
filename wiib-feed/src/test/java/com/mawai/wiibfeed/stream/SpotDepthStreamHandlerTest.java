package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibfeed.WsConnection;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 现货 depth20：报文没币名、没事件时间，币名从 stream 名取，时间记这一帧收到的时刻，data 原样存 */
class SpotDepthStreamHandlerTest {

    @Test
    void 从stream名取币名_按收到时刻存() {
        DepthStreamCache cache = mock(DepthStreamCache.class);
        WsConnection conn = mock(WsConnection.class);
        when(conn.frameReceivedAt()).thenReturn(1700000000123L);
        SpotDepthStreamHandler handler = new SpotDepthStreamHandler(new BinanceProperties(), cache);
        handler.bind(conn);
        String data = "{\"lastUpdateId\":160,\"bids\":[[\"37000.00\",\"1.5\"]],\"asks\":[[\"37000.01\",\"0.2\"]]}";

        handler.onMessage("{\"stream\":\"btcusdt@depth20@100ms\",\"data\":" + data + "}");

        verify(cache).onSpotDepthUpdate("BTCUSDT", data, 1700000000123L);
    }
}
