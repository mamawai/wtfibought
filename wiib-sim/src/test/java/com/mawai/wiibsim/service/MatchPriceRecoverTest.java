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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 空窗补漏按断的是哪条连接分：mark 拉 mark 1m K 线补逐仓强平和全仓精查，futures/spot（成交连接）交给成交消费者补。
 * 本进程启动只补标记价那段（按 last-tick 算），成交那段靠消费组接着读，last-tick 交给成交消费者给新建的组当起点。
 */
class MatchPriceRecoverTest {

    private static final String LAST_TICK_KEY = "sim:match:last-tick-ms";

    private final FuturesLiquidationService liquidationService = mock(FuturesLiquidationService.class);
    private final CrossLiquidationService crossLiq = mock(CrossLiquidationService.class);
    private final TradeStreamConsumer tradeStreamConsumer = mock(TradeStreamConsumer.class);
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
                liquidationService, crossLiq, tradeStreamConsumer, restClient, props);
    }

    private void gap(String kind, long from, long to) {
        String body = "{\"type\":\"gap\",\"kind\":\"" + kind + "\",\"from\":" + from + ",\"to\":" + to + "}";
        consumer().onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);
    }

    @Test
    void 标记价连接断过_拉mark_K线补强平() {
        when(props.getAllFuturesSymbols()).thenReturn(List.of("BTCUSDT"));
        when(restClient.markBars1m(eq("BTCUSDT"), anyLong(), anyLong())).thenReturn(BARS);

        gap("mark", 1700000000000L, 1700000600000L);

        verify(restClient, timeout(2000)).markBars1m("BTCUSDT", 1700000000000L, 1700000600000L);
        verify(liquidationService, timeout(2000)).recoverGap("BTCUSDT", BARS);
        verify(crossLiq, timeout(2000)).recoverGap("BTCUSDT", BARS);
        verify(tradeStreamConsumer, never()).recoverGap(anyBoolean(), anyLong(), anyLong());
    }

    @Test
    void 成交连接断过_交给成交消费者补() {
        gap("futures", 1700000000000L, 1700000600000L);
        gap("spot", 1700000000000L, 1700000600000L);

        verify(tradeStreamConsumer, timeout(2000)).recoverGap(true, 1700000000000L, 1700000600000L);
        verify(tradeStreamConsumer, timeout(2000)).recoverGap(false, 1700000000000L, 1700000600000L);
        verify(restClient, never()).markBars1m(any(), anyLong(), anyLong());
    }

    @Test
    void 空窗超1h不补漏() {
        gap("futures", 1700000000000L, 1700007200001L);

        verify(tradeStreamConsumer, after(500).never()).recoverGap(anyBoolean(), anyLong(), anyLong());
    }

    @Test
    void 启动有last_tick_成交消费者从它读起_标记价按它补漏() {
        long last = System.currentTimeMillis() - 3 * 60_000L;
        when(props.getAllFuturesSymbols()).thenReturn(List.of("BTCUSDT"));
        when(valueOps.get(LAST_TICK_KEY)).thenReturn(String.valueOf(last));

        consumer().init();

        verify(tradeStreamConsumer).start(last);
        verify(restClient, timeout(2000)).markBars1m(eq("BTCUSDT"), eq(last), longThat(to -> to >= last));
        verify(restClient, never()).futuresBars1m(any(), anyLong(), anyLong());
        verify(restClient, never()).spotBars1m(any(), anyLong(), anyLong());
    }

    @Test
    void 启动无last_tick不补漏() {
        when(valueOps.get(LAST_TICK_KEY)).thenReturn(null);

        consumer().init();

        verify(tradeStreamConsumer).start(0L);
        verify(restClient, after(300).never()).markBars1m(any(), anyLong(), anyLong());
    }

    @Test
    void 启动空窗超1h当第一次启动不补漏() {
        long last = System.currentTimeMillis() - 2 * 3_600_000L;
        when(valueOps.get(LAST_TICK_KEY)).thenReturn(String.valueOf(last));

        consumer().init();

        verify(tradeStreamConsumer).start(last);
        verify(restClient, after(300).never()).markBars1m(any(), anyLong(), anyLong());
    }
}
