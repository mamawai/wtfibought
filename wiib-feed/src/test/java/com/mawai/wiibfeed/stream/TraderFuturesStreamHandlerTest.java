package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibfeed.WsConnection;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 交易员的币的行情：两种帧只写价格键（标记价带收到时刻，连当期费率和下次结算时刻；最新价）；
 * 集合变了两条连接都换地址重连，没变不动，空了地址为空（连接闲置）。
 */
class TraderFuturesStreamHandlerTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final SetOperations<String, String> setOps = mock(SetOperations.class);
    private final CacheService cacheService = mock(CacheService.class);
    private final WsConnection conn = mock(WsConnection.class);
    private final WsConnection miniTickConn = mock(WsConnection.class);
    private final TraderFuturesStreamHandler handler = handler();

    private TraderFuturesStreamHandler handler() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(TraderFuturesStreamHandler.SYMBOLS_KEY)).thenReturn(Set.of());
        BinanceProperties props = new BinanceProperties();
        props.setFuturesWsUrl("wss://fstream.binance.com/ws");
        TraderFuturesStreamHandler h = new TraderFuturesStreamHandler(props, redisTemplate, cacheService,
                new FundingRateWriter(cacheService));
        h.init();
        h.bind(conn);
        h.setMiniTickConn(miniTickConn);
        return h;
    }

    @Test
    void 只写价格键_标记价带收到时刻连费率_最新价() {
        when(conn.frameReceivedAt()).thenReturn(1700000000150L);
        handler.handle("{\"stream\":\"pepeusdt@markPrice@1s\",\"data\":{\"e\":\"markPriceUpdate\",\"E\":1700000000000,"
                + "\"s\":\"PEPEUSDT\",\"p\":\"0.00001234\",\"i\":\"0.00001230\",\"P\":\"0.00001235\",\"r\":\"0.00010000\",\"T\":1700004800000}}");
        handler.handle("{\"stream\":\"pepeusdt@miniTicker\",\"data\":{\"e\":\"24hrMiniTicker\",\"E\":1700000000500,"
                + "\"s\":\"PEPEUSDT\",\"c\":\"0.00001233\",\"o\":\"0.00001200\",\"h\":\"0.00001300\",\"l\":\"0.00001100\",\"v\":\"1000\",\"q\":\"0.0123\"}}");

        verify(cacheService).setMarkPrice("PEPEUSDT", "0.00001234", 1700000000150L);
        verify(cacheService).putFundingRate(eq("PEPEUSDT"), eq(new BigDecimal("0.00010000")), eq(1700004800000L), anyLong());
        verify(cacheService).setFuturesPrice("PEPEUSDT", "0.00001233");
        verifyNoMoreInteractions(cacheService);
    }

    @Test
    void 集合变了换地址重连_没变不动_空了地址为空() {
        assertThat(handler.buildUrl()).isEmpty();
        assertThat(handler.miniTickUrl()).isEmpty();

        when(setOps.members(TraderFuturesStreamHandler.SYMBOLS_KEY)).thenReturn(Set.of("WIFUSDT", "PEPEUSDT"));
        handler.refresh();
        verify(conn).reconnectNow();
        verify(miniTickConn).reconnectNow();
        String mark = "wss://fstream.binance.com/market/stream?streams=pepeusdt@markPrice@1s/wifusdt@markPrice@1s";
        String mini = "wss://fstream.binance.com/market/stream?streams=pepeusdt@miniTicker/wifusdt@miniTicker";
        assertThat(handler.buildUrl()).isEqualTo(mark);
        assertThat(handler.miniTickUrl()).isEqualTo(mini);

        // 建连途中集合没变上去：下一轮按连接实际用的地址再比一次
        handler.refresh();
        verify(conn, times(2)).reconnectNow();
        when(conn.url()).thenReturn(mark);
        when(miniTickConn.url()).thenReturn(mini);
        handler.refresh();
        verify(conn, times(2)).reconnectNow();
        verify(miniTickConn, times(2)).reconnectNow();

        when(setOps.members(TraderFuturesStreamHandler.SYMBOLS_KEY)).thenReturn(Set.of());
        handler.refresh();
        verify(conn, times(3)).reconnectNow();
        verify(miniTickConn, times(3)).reconnectNow();
        assertThat(handler.buildUrl()).isEmpty();
    }
}
