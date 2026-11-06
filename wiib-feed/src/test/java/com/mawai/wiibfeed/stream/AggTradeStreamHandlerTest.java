package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import com.mawai.wiibfeed.WsConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 币安 aggTrade 一条消息 → 成交 Stream 一条：价、量原样字符串，带上这一帧收到的时刻，合约、现货各写各的 key；
 * 断过的空窗按连接发 gap，合约 kind=futures、现货 kind=spot
 */
class AggTradeStreamHandlerTest {

    private final TradeStreamWriter writer = mock(TradeStreamWriter.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final MatchPricePublisher publisher = mock(MatchPricePublisher.class);
    private final AggTradeStreamHandler futures = new AggTradeStreamHandler(new BinanceProperties(), writer,
            redisTemplate, publisher);
    private final SpotAggTradeStreamHandler spot = new SpotAggTradeStreamHandler(new BinanceProperties(), futures,
            redisTemplate, publisher);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void bind() {
        // 每帧记最后一帧时间，会写 Redis
        when(redisTemplate.opsForValue()).thenReturn(mock(ValueOperations.class));
        WsConnection futuresConn = mock(WsConnection.class);
        when(futuresConn.frameReceivedAt()).thenReturn(1700000000150L);
        futures.bind(futuresConn);
        WsConnection spotConn = mock(WsConnection.class);
        when(spotConn.frameReceivedAt()).thenReturn(1700000000250L);
        spot.bind(spotConn);
    }

    @Test
    void 合约组合流_写合约成交Stream() {
        futures.onMessage("{\"stream\":\"btcusdt@aggTrade\",\"data\":{\"e\":\"aggTrade\",\"E\":1700000000130,"
                + "\"a\":5933014,\"s\":\"BTCUSDT\",\"p\":\"37000.10\",\"q\":\"0.005\",\"f\":100,\"l\":105,"
                + "\"T\":1700000000123,\"m\":false}}");

        verify(writer).append(MarketStreamChannels.FUTURES_TRADES_PREFIX, "BTCUSDT", "37000.10", "0.005",
                false, 1700000000123L, 1700000000150L);
    }

    @Test
    void 现货_写现货成交Stream_小价格不变科学计数法() {
        // 现货多一个 M 字段；m=true 是主动卖
        spot.onMessage("{\"stream\":\"dogeusdt@aggTrade\",\"data\":{\"e\":\"aggTrade\",\"E\":1700000000200,"
                + "\"s\":\"DOGEUSDT\",\"a\":1,\"p\":\"0.00001234\",\"q\":\"12345678\",\"f\":1,\"l\":1,"
                + "\"T\":1700000000199,\"m\":true,\"M\":true}}");

        verify(writer).append(MarketStreamChannels.SPOT_TRADES_PREFIX, "DOGEUSDT", "0.00001234", "12345678",
                true, 1700000000199L, 1700000000250L);
    }

    @Test
    void 缺字段不写() {
        futures.onMessage("{\"stream\":\"btcusdt@aggTrade\",\"data\":{\"e\":\"aggTrade\",\"s\":\"BTCUSDT\"}}");
        futures.onMessage("not json");

        verify(writer, never()).append(anyString(), anyString(), anyString(), anyString(), anyBoolean(), anyLong(), anyLong());
    }

    @Test
    void 重连发空窗_合约是futures_现货是spot() {
        futures.onMessage("{}");
        spot.onMessage("{}");

        futures.onConnected(null);
        spot.onConnected(null);

        verify(publisher).publish(contains("\"kind\":\"futures\""));
        verify(publisher).publish(contains("\"kind\":\"spot\""));
    }

    @Test
    void 处理不过来挤掉过成交_按挤掉的那段发空窗() {
        futures.onDropped(1700000000100L, 1700000000900L);

        verify(publisher).publish(
                "{\"type\":\"gap\",\"kind\":\"futures\",\"from\":1700000000100,\"to\":1700000000900}");
    }
}
