package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 空窗补漏只有一条路：拉空窗段的 1m K 线交给四个撮合服务。
 * feed 连接重连发 gap 事件走这条，本进程启动按 last-tick 算出空窗也走这条；读不到 last-tick 或空窗超 1h 就一次都不拉。
 */
class MatchPriceRecoverTest {

    private static final String LAST_TICK_KEY = "sim:match:last-tick-ms";

    private final CryptoOrderService cryptoOrderService = mock(CryptoOrderService.class);
    private final FuturesLiquidationService liquidationService = mock(FuturesLiquidationService.class);
    private final FuturesSettlementService settlementService = mock(FuturesSettlementService.class);
    private final CrossLiquidationService crossLiq = mock(CrossLiquidationService.class);
    private final BinanceRestClient restClient = mock(BinanceRestClient.class);
    private final BinanceProperties props = mock(BinanceProperties.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private static final List<KlineBar> BARS = List.of(new KlineBar(0L, 59_999L,
            BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE));

    private MatchPriceConsumer consumer() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        return new MatchPriceConsumer(mock(RedisMessageListenerContainer.class), redisTemplate,
                cryptoOrderService, liquidationService, settlementService, crossLiq, restClient, props);
    }

    @Test
    void gap事件按空窗拉K线并喂给四个撮合服务() {
        when(props.getAllSpotSymbols()).thenReturn(List.of("BTCUSDT"));
        when(props.getAllFuturesSymbols()).thenReturn(List.of("BTCUSDT"));
        when(restClient.spotBars1m(eq("BTCUSDT"), anyLong(), anyLong())).thenReturn(BARS);
        when(restClient.futuresBars1m(eq("BTCUSDT"), anyLong(), anyLong())).thenReturn(BARS);
        when(restClient.markBars1m(eq("BTCUSDT"), anyLong(), anyLong())).thenReturn(BARS);

        String body = "{\"type\":\"gap\",\"kind\":\"futures\",\"from\":1700000000000,\"to\":1700000600000}";
        consumer().onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);

        // kind=futures 只走合约三家，现货不碰
        verify(restClient, timeout(2000)).futuresBars1m("BTCUSDT", 1700000000000L, 1700000600000L);
        verify(restClient, timeout(2000)).markBars1m("BTCUSDT", 1700000000000L, 1700000600000L);
        verify(settlementService, timeout(2000)).recoverGap("BTCUSDT", BARS);
        verify(liquidationService, timeout(2000)).recoverGap("BTCUSDT", BARS, BARS);
        verify(crossLiq, timeout(2000)).recoverGap("BTCUSDT", BARS);
        verify(restClient, never()).spotBars1m(any(), anyLong(), anyLong());
    }

    @Test
    void gap事件kind为spot只走现货() {
        when(props.getAllSpotSymbols()).thenReturn(List.of("BTCUSDT"));
        when(restClient.spotBars1m(eq("BTCUSDT"), anyLong(), anyLong())).thenReturn(BARS);

        String body = "{\"type\":\"gap\",\"kind\":\"spot\",\"from\":1700000000000,\"to\":1700000600000}";
        consumer().onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);

        verify(cryptoOrderService, timeout(2000)).recoverGap("BTCUSDT", BARS);
        verify(restClient, never()).futuresBars1m(any(), anyLong(), anyLong());
    }

    @Test
    void 启动有last_tick按它当空窗起点() {
        long last = System.currentTimeMillis() - 3 * 60_000L;
        when(props.getAllSpotSymbols()).thenReturn(List.of("BTCUSDT"));
        when(props.getAllFuturesSymbols()).thenReturn(List.of("BTCUSDT"));
        when(valueOps.get(LAST_TICK_KEY)).thenReturn(String.valueOf(last));

        consumer().init();

        verify(restClient, timeout(2000)).spotBars1m(eq("BTCUSDT"), eq(last), longThat(to -> to >= last));
        verify(restClient, timeout(2000)).futuresBars1m(eq("BTCUSDT"), eq(last), longThat(to -> to >= last));
        verify(restClient, timeout(2000)).markBars1m(eq("BTCUSDT"), eq(last), longThat(to -> to >= last));
    }

    @Test
    void 启动无last_tick不补漏() {
        when(valueOps.get(LAST_TICK_KEY)).thenReturn(null);

        consumer().init();

        verify(restClient, never()).spotBars1m(any(), anyLong(), anyLong());
        verify(restClient, never()).futuresBars1m(any(), anyLong(), anyLong());
        verify(restClient, never()).markBars1m(any(), anyLong(), anyLong());
    }

    @Test
    void 启动空窗超1h当第一次启动不补漏() {
        when(valueOps.get(LAST_TICK_KEY)).thenReturn(String.valueOf(System.currentTimeMillis() - 2 * 3_600_000L));

        consumer().init();

        verify(restClient, never()).spotBars1m(any(), anyLong(), anyLong());
        verify(restClient, never()).futuresBars1m(any(), anyLong(), anyLong());
    }

    @Test
    void gap事件空窗超1h不补漏() {
        String body = "{\"type\":\"gap\",\"kind\":\"futures\",\"from\":1700000000000,\"to\":1700007200001}";
        consumer().onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);

        // 分发在虚拟线程里，等一会再确认一次都没拉
        verify(restClient, after(500).never()).futuresBars1m(any(), anyLong(), anyLong());
        verify(restClient, never()).markBars1m(any(), anyLong(), anyLong());
    }
}
