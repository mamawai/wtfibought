package com.mawai.wiibagent.learning;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事实表的路径指标：多空两向、到目标 25%/50% 用时、第一次收回入场价、出场后最有利价与是否到过目标；
 * 没 K 线时全空；分组排序与案例选择。
 */
class CaseFileBuilderTest {

    /** 整点对齐的起点 */
    private static final long T0 = 1791540000000L;
    private static final long M5 = 300_000L;

    private final KlineHistoryStore store = mock(KlineHistoryStore.class);
    private final CaseFileBuilder builder = new CaseFileBuilder(store);

    @Test
    void longTradePathMetrics() {
        List<KlineBar> bars = new ArrayList<>();
        double[][] in = {
                {100, 101, 99.5, 100.5},
                {100.5, 103, 100, 102.5},
                {102.5, 105.6, 102, 104},
                {104, 104.5, 101, 101.5},
                {101.5, 102, 100.2, 100.3},
                {100.3, 100.8, 98.5, 99},
                {99, 99.5, 98, 98.5},
                {98.5, 99, 97.8, 98.2},
                {98.2, 98.6, 97.5, 98},
                {98, 98.5, 96, 96.5},
                {96.5, 97, 95.5, 96},
                {96, 96.5, 95.2, 95}};
        for (int i = 0; i < in.length; i++) {
            bars.add(bar(T0 + i * M5, in[i]));
        }
        bars.add(bar(T0 + 13 * M5, new double[]{95, 98, 94.8, 97}));
        bars.add(bar(T0 + 36 * M5, new double[]{97, 111, 96, 110.5}));
        bars.add(bar(T0 + 120 * M5, new double[]{110, 112, 109, 111}));
        // 出场根是 T0+55m，这根覆盖到出场后 24h
        bars.add(bar(T0 + 299 * M5, new double[]{111, 111.5, 110, 111}));
        when(store.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(bars);

        FuturesPositionDTO pos = position("LONG", "100", "95", T0, T0 + 57 * 60_000L);
        AiTraderPlan plan = plan("95", "110", "{\"regime\":\"RANGE\"}");

        CaseFileBuilder.TradeCase c = builder.build(pos, plan);

        assertThat(c.entryCtx().path("regime").asString()).isEqualTo("RANGE");
        CaseFileBuilder.Path p = c.path();
        assertThat(p.mfePrice()).isEqualByComparingTo("105.6");
        assertThat(p.mfeAt()).isEqualTo(T0 + 2 * M5);
        assertThat(p.mfeFrac()).isEqualByComparingTo("0.56");
        assertThat(p.maePrice()).isEqualByComparingTo("95.2");
        assertThat(p.maeFrac()).isEqualByComparingTo("0.96");
        assertThat(p.minTo25()).isEqualTo(5);
        assertThat(p.minTo50()).isEqualTo(10);
        assertThat(p.firstBackAt()).isEqualTo(T0 + 5 * M5);
        assertThat(p.hourly()).hasSize(1);
        assertThat(p.hourly().getFirst().high()).isEqualByComparingTo("105.6");
        assertThat(p.hourly().getFirst().close()).isEqualByComparingTo("95");
        assertThat(p.bestAfter4h()).isEqualByComparingTo("111");
        assertThat(p.bestAfter24h()).isEqualByComparingTo("112");
        assertThat(p.targetAfter24h()).isTrue();
    }

    @Test
    void shortTradeMirrorsDirections() {
        List<KlineBar> bars = new ArrayList<>();
        double[][] in = {
                {100, 100.5, 99, 99.5},
                {99.5, 100, 97, 97.5},
                {97.5, 98, 94, 95},
                {95, 99, 94.5, 98.5},
                {98.5, 103, 98, 101},
                {101, 104.9, 100.5, 104.8}};
        for (int i = 0; i < in.length; i++) {
            bars.add(bar(T0 + i * M5, in[i]));
        }
        bars.add(bar(T0 + 30 * M5, new double[]{104, 105, 89, 90.5}));
        // 出场根是 T0+25m，这根刚好覆盖到出场后 4h；24h 覆盖不到
        bars.add(bar(T0 + 53 * M5, new double[]{90.5, 91, 90, 90.5}));
        when(store.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(bars);

        FuturesPositionDTO pos = position("SHORT", "100", "104.8", T0, T0 + 27 * 60_000L);
        CaseFileBuilder.Path p = builder.build(pos, plan("105", "90", null)).path();

        assertThat(p.mfePrice()).isEqualByComparingTo("94");
        assertThat(p.mfeFrac()).isEqualByComparingTo("0.60");
        assertThat(p.maePrice()).isEqualByComparingTo("104.9");
        assertThat(p.maeFrac()).isEqualByComparingTo("0.98");
        assertThat(p.minTo25()).isEqualTo(5);
        assertThat(p.minTo50()).isEqualTo(10);
        assertThat(p.firstBackAt()).isEqualTo(T0 + 4 * M5);
        assertThat(p.bestAfter4h()).isEqualByComparingTo("89");
        assertThat(p.bestAfter24h()).isNull();
        assertThat(p.targetAfter24h()).isNull();
    }

    /** 出场后一根都覆盖不到 4h：三个出场后指标全空 */
    @Test
    void afterExitCoverageUnderFourHoursLeavesAfterMetricsNull() {
        when(store.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                bar(T0, new double[]{100, 101, 99.5, 100.5}),
                bar(T0 + 2 * M5, new double[]{100.5, 103, 100, 102.5})));

        CaseFileBuilder.Path p = builder.build(position("LONG", "100", "100.5", T0, T0 + 6 * 60_000L), plan("95", "110", null)).path();

        assertThat(p.mfePrice()).isEqualByComparingTo("101");
        assertThat(p.bestAfter4h()).isNull();
        assertThat(p.bestAfter24h()).isNull();
        assertThat(p.targetAfter24h()).isNull();
    }

    @Test
    void noBarsGivesEmptyPath() {
        when(store.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of());

        CaseFileBuilder.Path p = builder.build(position("LONG", "100", "95", T0, T0 + M5), null).path();

        assertThat(p.mfePrice()).isNull();
        assertThat(p.mfeFrac()).isNull();
        assertThat(p.minTo25()).isNull();
        assertThat(p.hourly()).isEmpty();
        assertThat(p.targetAfter24h()).isNull();
    }

    /**
     * 重放：多单入场 100、止损 98（R=2）、目标 106。开仓那根不算；止损原样在最低 97.6 那根被打；
     * 下一根既到 106.5 又到 96.5：1.5 倍止损（97）同根两头都碰到按止损算，2 倍（96）和 3 倍（94）没碰到算到目标。
     */
    @Test
    void replayWalksEachStopWidthFromTheBarAfterEntry() {
        when(store.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                bar(T0, new double[]{100, 110, 90, 100}),
                bar(T0 + M5, new double[]{100, 103, 99.5, 102}),
                bar(T0 + 2 * M5, new double[]{102, 102.5, 97.6, 98.5}),
                bar(T0 + 3 * M5, new double[]{98.5, 106.5, 96.5, 105}),
                bar(T0 + 287 * M5, new double[]{105, 105.5, 104, 104.5})));
        FuturesPositionDTO pos = position("LONG", "100", "98", T0 + 60_000L, T0 + 12 * 60_000L);
        pos.setOpenedQuantity(new BigDecimal("10"));

        CaseFileBuilder.Replay r = builder.build(pos, plan("98", "106", null)).replay();

        // 净 -50 ÷（10 × 2）
        assertThat(r.actualR()).isEqualByComparingTo("-2.5");
        assertThat(r.fav24R()).isEqualByComparingTo("3.25");
        assertThat(r.adv24R()).isEqualByComparingTo("-1.75");
        assertThat(r.hold24R()).isEqualByComparingTo("2.25");
        assertThat(r.widths()).extracting(CaseFileBuilder.WidthOutcome::outcome).containsExactly(
                CaseFileBuilder.Outcome.STOP, CaseFileBuilder.Outcome.STOP,
                CaseFileBuilder.Outcome.TARGET, CaseFileBuilder.Outcome.TARGET);
        // 到目标按同样风险折算：目标距离 6 ÷ 止损距离 4、6
        assertThat(r.widths().get(2).r()).isEqualByComparingTo("1.5");
        assertThat(r.widths().get(3).r()).isEqualByComparingTo("1");
        assertThat(r.untouched().r()).isEqualByComparingTo("-1");
    }

    /** K 线没覆盖到开仓后 24 小时：拿满 24 小时的位置留空，两头都没碰到的记未决 */
    @Test
    void replayLeavesHoldBlankAndWidthsOpenWhenBarsRunOut() {
        when(store.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                bar(T0, new double[]{100, 100.5, 99.8, 100}),
                bar(T0 + M5, new double[]{100, 101, 99, 100.5})));

        CaseFileBuilder.Replay r = builder.replay(position("SHORT", "100", "100.5", T0, T0 + 6 * 60_000L), plan("102", "95", null));

        assertThat(r.actualR()).isNull();
        assertThat(r.fav24R()).isEqualByComparingTo("0.5");
        assertThat(r.adv24R()).isEqualByComparingTo("-0.5");
        assertThat(r.hold24R()).isNull();
        assertThat(r.widths()).allMatch(w -> w.outcome() == CaseFileBuilder.Outcome.OPEN && w.r() == null);
    }

    /**
     * 拿线上真 K 线对一遍 beta 那 16 笔（计划附录 B）：每行是 币 方向 开仓日 时:分（北京）入场 原止损 原目标 止损原样的结局 止损两倍的结局。
     * K 线到 11-10 09:25 为止，之后没数据的记未决。
     */
    @Test
    void replayMatchesHandCheckedTradesOnRealBars() throws Exception {
        Map<String, List<KlineBar>> bySymbol = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(getClass().getResource("/replay/bars-5m-2026-11-07.csv").toURI()))) {
            String[] f = line.split(",");
            long open = Long.parseLong(f[1]);
            bySymbol.computeIfAbsent(f[0], k -> new ArrayList<>()).add(new KlineBar(open, open + M5 - 1,
                    new BigDecimal(f[2]), new BigDecimal(f[3]), new BigDecimal(f[4]), new BigDecimal(f[5]), BigDecimal.ZERO));
        }
        when(store.load(anyString(), anyString(), anyLong(), anyLong())).thenAnswer(inv -> {
            long from = inv.getArgument(2);
            long to = inv.getArgument(3);
            return bySymbol.get(inv.<String>getArgument(0)).stream()
                    .filter(b -> b.openTime() >= from && b.openTime() < to).toList();
        });
        String[] trades = {
                "ZECUSDT LONG 7 16:25 1319.9 1292.8 1387.8 STOP STOP",
                "HYPEUSDT LONG 7 21:46 78.003 77.12 79.55 STOP STOP",
                "ZECUSDT SHORT 8 00:12 1266.5 1289.0 1212.5 STOP TARGET",
                "HYPEUSDT SHORT 8 01:24 77.45 77.89 76.24 STOP TARGET",
                "ETHUSDT SHORT 8 06:18 2232.6 2240.8 2205.2 STOP TARGET",
                "HYPEUSDT SHORT 8 06:45 77.38 77.88 76.24 STOP TARGET",
                "HYPEUSDT SHORT 8 09:02 77.708 78.22 76.24 TARGET TARGET",
                "ZECUSDT SHORT 8 19:15 1271.0 1283.5 1235.3 STOP TARGET",
                "HYPEUSDT SHORT 8 21:14 77.84 78.22 76.24 TARGET TARGET",
                "ZECUSDT LONG 9 02:10 1248.5 1235.5 1278.0 STOP STOP",
                "ETHUSDT LONG 9 07:35 2145.4 2124.5 2205.1 STOP STOP",
                "HYPEUSDT LONG 9 07:35 75.14 74.44 76.68 STOP STOP",
                "HYPEUSDT SHORT 9 16:41 73.40 74.06 71.50 STOP STOP",
                "ZECUSDT SHORT 9 16:56 1231.5 1252.5 1177.0 TARGET TARGET",
                "ETHUSDT SHORT 9 19:17 2118.0 2140.3 2050.3 STOP OPEN",
                "HYPEUSDT SHORT 9 19:17 73.68 74.52 71.50 STOP STOP"};
        for (String trade : trades) {
            String[] f = trade.split(" ");
            String[] hm = f[3].split(":");
            long openMs = ZonedDateTime.of(2026, 11, Integer.parseInt(f[2]), Integer.parseInt(hm[0]), Integer.parseInt(hm[1]),
                    0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            FuturesPositionDTO pos = position(f[1], f[4], f[4], openMs, openMs + M5);
            pos.setSymbol(f[0]);

            CaseFileBuilder.Replay r = builder.replay(pos, plan(f[5], f[6], null));

            assertThat(r.widths().get(0).outcome()).as("%s 止损原样", trade).isEqualTo(CaseFileBuilder.Outcome.valueOf(f[7]));
            assertThat(r.widths().get(2).outcome()).as("%s 止损两倍", trade).isEqualTo(CaseFileBuilder.Outcome.valueOf(f[8]));
        }
    }

    @Test
    void groupsPreferRepeatedSetupsThenSize() {
        List<CaseFileBuilder.TradeCase> cases = List.of(
                tradeCase("BREAKOUT", "900"),
                tradeCase("PULLBACK", "-100"),
                tradeCase(null, "-5"),
                tradeCase("PULLBACK", "50"));

        List<CaseFileBuilder.SetupGroup> groups = CaseFileBuilder.groups(cases);

        assertThat(groups).extracting(CaseFileBuilder.SetupGroup::playType).containsExactly("PULLBACK", "BREAKOUT", null);
        assertThat(groups.getFirst().wins()).isEqualTo(1);
        assertThat(groups.getFirst().net()).isEqualByComparingTo("-50");
        assertThat(CaseFileBuilder.select(groups)).hasSize(2);
    }

    @Test
    void marketWindowTakesExtremesWithTimes() {
        when(store.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                bar(T0, new double[]{100, 101, 99, 100.5}),
                bar(T0 + M5, new double[]{100.5, 108, 100, 107}),
                bar(T0 + 2 * M5, new double[]{107, 107.5, 98, 99})));

        CaseFileBuilder.MarketWindow w = builder.market("BTCUSDT", T0, T0 + 3 * M5);

        assertThat(w.from()).isEqualTo(T0);
        assertThat(w.to()).isEqualTo(T0 + 3 * M5);
        assertThat(w.changePct()).isEqualByComparingTo("-1.00");
        assertThat(w.open()).isEqualByComparingTo("100");
        assertThat(w.high()).isEqualByComparingTo("108");
        assertThat(w.highAt()).isEqualTo(T0 + M5);
        assertThat(w.low()).isEqualByComparingTo("98");
        assertThat(w.lowAt()).isEqualTo(T0 + 2 * M5);
        assertThat(w.close()).isEqualByComparingTo("99");
        assertThat(w.lowToHighPct()).isEqualByComparingTo("10.20");
    }

    private static KlineBar bar(long openTime, double[] ohlc) {
        return new KlineBar(openTime, openTime + M5 - 1, BigDecimal.valueOf(ohlc[0]), BigDecimal.valueOf(ohlc[1]),
                BigDecimal.valueOf(ohlc[2]), BigDecimal.valueOf(ohlc[3]), BigDecimal.TEN);
    }

    private static FuturesPositionDTO position(String side, String entry, String exit, long openMs, long closeMs) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(1L);
        p.setSymbol("BTCUSDT");
        p.setSide(side);
        p.setStatus("CLOSED");
        p.setEntryPrice(new BigDecimal(entry));
        p.setClosedPrice(new BigDecimal(exit));
        p.setClosedPnl(new BigDecimal("-50"));
        p.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(openMs), ZoneId.systemDefault()));
        p.setUpdatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(closeMs), ZoneId.systemDefault()));
        FuturesStopLoss sl = new FuturesStopLoss();
        sl.setPrice(new BigDecimal(exit));
        p.setStopLosses(List.of(sl));
        return p;
    }

    private static AiTraderPlan plan(String stop, String target, String ctx) {
        AiTraderPlan plan = new AiTraderPlan();
        plan.setPlayType("PULLBACK");
        plan.setStopLossPrice(new BigDecimal(stop));
        plan.setTakeProfitPrice(new BigDecimal(target));
        plan.setEntryCtxJson(ctx);
        return plan;
    }

    private static CaseFileBuilder.TradeCase tradeCase(String playType, String net) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setClosedPnl(new BigDecimal(net));
        AiTraderPlan plan = new AiTraderPlan();
        plan.setPlayType(playType);
        return new CaseFileBuilder.TradeCase(p, plan, null, CaseFileBuilder.Path.EMPTY, null);
    }
}
