package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 读成交 Stream：新建的消费组从 sim 上次活着的时刻读起，停机超过 1 小时从现在读起；启动先重放没 ACK 的那批，再读新的；
 * 每批判完才 ACK；消费组读到的位置早于 Stream 最早一条，中间被裁掉的那段拉 K 线补。
 */
class TradeStreamConsumerTest {

    private static final String KEY = "market:orderflow:BTCUSDT";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
    private final BinanceProperties props = mock(BinanceProperties.class);
    private final BinanceRestClient rest = mock(BinanceRestClient.class);
    private final CacheService cache = mock(CacheService.class);
    private final FuturesSettlementService settlement = mock(FuturesSettlementService.class);
    private final FuturesLiquidationService liquidation = mock(FuturesLiquidationService.class);
    private final CryptoOrderService crypto = mock(CryptoOrderService.class);
    private final TraderSymbolService traderSymbols = mock(TraderSymbolService.class);
    private final TradeStreamConsumer consumer = new TradeStreamConsumer(redis, props, rest, cache,
            settlement, liquidation, crypto, traderSymbols);

    @BeforeEach
    void setUp() {
        when(redis.opsForStream()).thenReturn(ops);
        when(props.getAllFuturesSymbols()).thenReturn(List.of("BTCUSDT"));
        when(props.getAllSpotSymbols()).thenReturn(List.of());
        when(settlement.limitHits(anyString(), anyList())).thenReturn(List.of());
        when(liquidation.stopHits(anyString(), anyList())).thenReturn(List.of());
        when(crypto.limitHits(anyString(), anyList())).thenReturn(List.of());
    }

    @AfterEach
    void stopReading() {
        consumer.stop();
    }

    private static MapRecord<String, Object, Object> trade(String id, String price, long rt) {
        Map<Object, Object> fields = Map.of("ts", String.valueOf(rt), "p", price, "q", "1", "bm", "0",
                "rt", String.valueOf(rt));
        return StreamRecords.newRecord().in(KEY).withId(RecordId.of(id)).ofMap(fields);
    }

    private static KlineBar bar(long openTime, String price) {
        BigDecimal p = new BigDecimal(price);
        return new KlineBar(openTime, openTime + 59_999L, p, p, p, p, BigDecimal.ONE);
    }

    private static boolean prices(List<PriceTick> ticks, String... expected) {
        if (ticks.size() != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (ticks.get(i).low().compareTo(new BigDecimal(expected[i])) != 0) return false;
        }
        return true;
    }

    @Test
    void 新建的消费组从上次活着的时刻读起() {
        long last = System.currentTimeMillis() - 60_000L;

        consumer.start(last);

        verify(ops, timeout(2000)).createGroup(KEY, ReadOffset.from(last + "-0"), "sim");
    }

    @Test
    void 没有上次活着的时刻_新建的组从现在读起() {
        consumer.start(0L);

        verify(ops, timeout(2000)).createGroup(KEY, ReadOffset.latest(), "sim");
        verify(ops, after(300).never()).destroyGroup(anyString(), anyString());
    }

    /** 停机超过 1 小时当第一次启动：已有的组连同没 ACK 的一起扔掉，从现在读起，停机那段不补 */
    @Test
    void 停机超过1小时_已有的组跳到现在() {
        when(ops.createGroup(KEY, ReadOffset.latest(), "sim"))
                .thenThrow(new RedisSystemException("Error in execution",
                        new RuntimeException("BUSYGROUP Consumer Group name already exists")))
                .thenReturn("OK");

        consumer.start(System.currentTimeMillis() - 2 * 3_600_000L);

        verify(ops, timeout(2000)).destroyGroup(KEY, "sim");
        verify(ops, timeout(2000).times(2)).createGroup(KEY, ReadOffset.latest(), "sim");
        verify(rest, never()).futuresBars1m(anyString(), anyLong(), anyLong());
    }

    @Test
    void 先重放没ACK的那批_再读新的_每批判完才ACK() {
        long now = System.currentTimeMillis();
        Deque<List<MapRecord<String, Object, Object>>> replies = new ArrayDeque<>(List.of(
                List.of(trade("1-0", "100", now)),
                List.of(),
                List.of(trade("2-0", "101", now), trade("3-0", "99", now))));
        List<ReadOffset> offsets = new CopyOnWriteArrayList<>();
        when(ops.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset[].class))).thenAnswer(inv -> {
            offsets.add(inv.<StreamOffset<String>>getArgument(2).getOffset());
            List<MapRecord<String, Object, Object>> r = replies.poll();
            return r == null ? List.of() : r;
        });

        consumer.start(0L);

        verify(ops, timeout(2000)).acknowledge(KEY, "sim", RecordId.of("2-0"), RecordId.of("3-0"));
        InOrder inOrder = inOrder(settlement, ops);
        inOrder.verify(settlement).limitHits(eq("BTCUSDT"), argThat(t -> prices(t, "100")));
        inOrder.verify(ops).acknowledge(KEY, "sim", RecordId.of("1-0"));
        inOrder.verify(settlement).limitHits(eq("BTCUSDT"), argThat(t -> prices(t, "101", "99")));
        inOrder.verify(ops).acknowledge(KEY, "sim", RecordId.of("2-0"), RecordId.of("3-0"));
        // 没 ACK 的从头往后过一遍（0、再从刚处理完的 1-0 往后），之后读新的（>）。
        // 消费线程还在循环读、往 offsets 里加：取快照再断言，COW 的 subList 边加边遍历会 ConcurrentModification
        assertThat(offsets.stream().limit(3).toList()).containsExactly(ReadOffset.from("0"), ReadOffset.from("1-0"),
                ReadOffset.lastConsumed());
    }

    @Test
    void 消费组读到的位置早于Stream最早一条_中间那段拉K线补() {
        long from = 1_700_000_000_000L;
        long to = from + 60_000L;
        StreamInfo.XInfoGroup group = mock(StreamInfo.XInfoGroup.class);
        when(group.groupName()).thenReturn("sim");
        when(group.lastDeliveredId()).thenReturn(from + "-0");
        StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
        when(groups.stream()).thenReturn(Stream.of(group));
        when(ops.groups(KEY)).thenReturn(groups);
        when(ops.range(eq(KEY), any(), any())).thenReturn(List.of(trade(to + "-0", "100", to)));
        when(rest.futuresBars1m("BTCUSDT", from, to)).thenReturn(List.of(bar(from, "100")));

        // 停机不到 1 小时，接着上次读
        consumer.start(System.currentTimeMillis() - 60_000L);

        // 补漏的行情是旧价：时刻是那根 K 线开盘
        verify(settlement, timeout(2000)).limitHits(eq("BTCUSDT"),
                argThat(t -> t.size() == 1 && t.getFirst().at() == from));
        verify(liquidation, timeout(2000)).stopHits(eq("BTCUSDT"), anyList());
    }

    /** 巡的合约币包括交易员的币（PEPEUSDT 没有成交 Stream，只靠巡检） */
    @Test
    void 兜底巡检拿缓存的最新价当一笔成交_没价的币跳过() {
        when(traderSymbols.allFuturesSymbols()).thenReturn(new LinkedHashSet<>(List.of("BTCUSDT", "PEPEUSDT")));
        when(props.getAllSpotSymbols()).thenReturn(List.of("ETHUSDT"));
        when(cache.getFuturesPrice("BTCUSDT")).thenReturn(new BigDecimal("100"));
        when(cache.getFuturesPrice("PEPEUSDT")).thenReturn(new BigDecimal("0.00001"));

        consumer.sweep();

        verify(settlement).limitHits(eq("BTCUSDT"), argThat(t -> prices(t, "100")));
        verify(liquidation).stopHits(eq("BTCUSDT"), argThat(t -> prices(t, "100")));
        verify(liquidation).stopHits(eq("PEPEUSDT"), argThat(t -> prices(t, "0.00001")));
        verify(crypto, never()).limitHits(anyString(), anyList());
    }

    /** 判限价单出错不能连累同一批的止损止盈 */
    @Test
    void 判限价单出错_止损止盈照判() {
        when(traderSymbols.allFuturesSymbols()).thenReturn(new LinkedHashSet<>(List.of("BTCUSDT")));
        when(cache.getFuturesPrice("BTCUSDT")).thenReturn(new BigDecimal("100"));
        when(settlement.limitHits(anyString(), anyList())).thenThrow(new RuntimeException("数据库抖了一下"));

        consumer.sweep();

        verify(liquidation).stopHits(eq("BTCUSDT"), anyList());
    }

    @Test
    void 现货成交连接断过_每个币拉现货K线补() {
        when(props.getAllSpotSymbols()).thenReturn(List.of("ETHUSDT"));
        when(rest.spotBars1m("ETHUSDT", 1L, 2L)).thenReturn(List.of(bar(0L, "3000")));

        consumer.recoverGap(false, 1L, 2L);

        verify(crypto).limitHits(eq("ETHUSDT"), argThat(t -> prices(t, "3000")));
        verify(settlement, never()).limitHits(anyString(), anyList());
    }
}
