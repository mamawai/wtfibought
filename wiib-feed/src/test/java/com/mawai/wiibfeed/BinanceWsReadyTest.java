package com.mawai.wiibfeed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 交接时新 feed 算不算连好出数：闲置的算；连上的要收到过业务帧，冷门流连上 5 秒也算；在连、在重连的不算 */
class BinanceWsReadyTest {

    private static final long NOW = 1_786_200_000_000L;

    private static WsConnection conn(WsConnection.Status status, long connectedAt, long lastMessageAt) {
        WsConnection c = mock(WsConnection.class);
        when(c.status()).thenReturn(status);
        when(c.connectedAt()).thenReturn(connectedAt);
        when(c.lastMessageAt()).thenReturn(lastMessageAt);
        return c;
    }

    @Test
    void 连上收到过帧算好() {
        assertThat(BinanceWsClient.ready(conn(WsConnection.Status.CONNECTED, NOW - 800, NOW - 100), NOW)).isTrue();
    }

    @Test
    void 刚连上还没帧不算_连上5秒没帧也算() {
        assertThat(BinanceWsClient.ready(conn(WsConnection.Status.CONNECTED, NOW - 800, NOW - 800), NOW)).isFalse();
        assertThat(BinanceWsClient.ready(conn(WsConnection.Status.CONNECTED, NOW - 5_000, NOW - 5_000), NOW)).isTrue();
    }

    @Test
    void 闲置算好_在连在重连不算() {
        assertThat(BinanceWsClient.ready(conn(WsConnection.Status.IDLE, 0, 0), NOW)).isTrue();
        assertThat(BinanceWsClient.ready(conn(WsConnection.Status.CONNECTING, 0, 0), NOW)).isFalse();
        assertThat(BinanceWsClient.ready(conn(WsConnection.Status.RECONNECTING, NOW - 9_000, NOW - 9_000), NOW)).isFalse();
    }
}
