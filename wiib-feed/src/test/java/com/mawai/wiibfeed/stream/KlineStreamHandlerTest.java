package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibfeed.KlineStreamCache;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 币安 kline WS 一条消息：广播给前端的串 + 收盘 bar 喂策略 */
class KlineStreamHandlerTest {

    private final KlineStreamCache cache = mock(KlineStreamCache.class);
    private final MarketBroadcaster broadcaster = mock(MarketBroadcaster.class);
    private final KlineStreamHandler handler = new KlineStreamHandler(new BinanceProperties(), cache, broadcaster);

    /** 组合流外面包一层 {stream,data}；价量是字符串 */
    private static String msg(boolean closed) {
        return "{\"stream\":\"btcusdt@kline_5m\",\"data\":{\"e\":\"kline\",\"E\":1700000123456,\"s\":\"BTCUSDT\","
                + "\"k\":{\"t\":1700000100000,\"T\":1700000399999,\"s\":\"BTCUSDT\",\"i\":\"5m\","
                + "\"o\":\"37000.10\",\"c\":\"37010.50\",\"h\":\"37020.00\",\"l\":\"36990.00\","
                + "\"v\":\"12.345\",\"n\":100,\"x\":" + closed + ",\"q\":\"456789.12\",\"V\":\"6.1\",\"Q\":\"225000.5\",\"B\":\"0\"}}}";
    }

    @Test
    void 广播串字段与格式不变() {
        handler.handle(msg(false), true);

        verify(broadcaster).broadcastKline("BTCUSDT",
                "{\"i\":\"5m\",\"t\":1700000100000,\"o\":\"37000.10\",\"h\":\"37020.00\",\"l\":\"36990.00\","
                        + "\"c\":\"37010.50\",\"v\":\"12.345\",\"q\":\"456789.12\",\"x\":false}");
        verify(cache, never()).onClosedBar(anyString(), anyString(), any());
    }

    @Test
    void 收盘bar喂策略_价量保留原字面量() {
        handler.handle(msg(true), true);

        verify(cache).onClosedBar("BTCUSDT", "5m", new KlineBar(1700000100000L, 1700000399999L,
                new BigDecimal("37000.10"), new BigDecimal("37020.00"), new BigDecimal("36990.00"),
                new BigDecimal("37010.50"), new BigDecimal("12.345")));
    }

    @Test
    void 副连接只广播不喂策略() {
        handler.handle(msg(true), false);

        verify(broadcaster).broadcastKline(anyString(), anyString());
        verify(cache, never()).onClosedBar(anyString(), anyString(), any());
    }

    @Test
    void 单流格式没有data包装也能解析() {
        String raw = "{\"e\":\"kline\",\"s\":\"ETHUSDT\",\"k\":{\"t\":1,\"T\":2,\"s\":\"ETHUSDT\",\"i\":\"5m\","
                + "\"o\":\"1\",\"c\":\"2\",\"h\":\"3\",\"l\":\"0.5\",\"v\":\"9\",\"x\":true,\"q\":\"10\"}}";
        handler.handle(raw, true);

        verify(broadcaster).broadcastKline("ETHUSDT",
                "{\"i\":\"5m\",\"t\":1,\"o\":\"1\",\"h\":\"3\",\"l\":\"0.5\",\"c\":\"2\",\"v\":\"9\",\"q\":\"10\",\"x\":true}");
        verify(cache).onClosedBar("ETHUSDT", "5m", new KlineBar(1L, 2L,
                new BigDecimal("1"), new BigDecimal("3"), new BigDecimal("0.5"), new BigDecimal("2"), new BigDecimal("9")));
    }

    @Test
    void 坏消息不抛出() {
        handler.handle("not json", true);
        handler.handle("", true);
        handler.handle("{\"data\":{\"s\":\"BTCUSDT\"}}", true);

        verify(broadcaster, never()).broadcastKline(anyString(), anyString());
    }
}
