package com.mawai.wiibquant.agent.strategy.fibo;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.WindowedMarketView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FiboRetracementStrategyTest {

    private static final long M5 = 5 * 60_000L;
    private static final String SYM = "BTCUSDT";

    /**
     * 限价回踩范式快测参数：swing=5m(每根=1摆动bar，便于精确控盘)、挂单超时设极大(测试期内不超时)。
     * 趋势门关掉保持方向无关纯回踩；信号发射/挂单逻辑在此独立验证，整体效果由 FiboStrategyDbRun 在真实数据上验证。
     */
    private static FiboParams fast() {
        return new FiboParams(
                M5,                    // swing=5m
                3, 1.0, 2.0,           // atrPeriod, reversalAtrMult, minLegAtrMult
                0.618, 0.786, 0.786,   // entryFib, invalidationRatio, slFibRatio
                0.1, 1.0,              // slBufferAtrMult, tpExtensionRatio
                100_000, 500,          // orderTimeoutBars(极大), swingLookbackBars
                0.0,                   // tpRMultiple(0=用延伸位)
                false,                 // trendFilterOn(测试保持方向无关纯回踩)
                false);                // trendAlignOn
    }

    @Test
    void emitsLongLimitOrderAtGoldenPocketWhenPriceHoldsAboveEntry() {
        FiboRetracementStrategy strategy = new FiboRetracementStrategy(fast(), List.of(SYM));
        WindowedMarketView view = new WindowedMarketView(20_000);
        List<StrategySignal> signals = new ArrayList<>();

        // 上行推动腿 100→300(确认 LOW@114→HIGH@301)，再一根大幅回撤确认HIGH、收220(仍在0.618位185.4上方)
        feedRising(strategy, view, signals, 100, 40, 5);
        feed(strategy, view, 300, 301, 218, 220).ifPresent(signals::add);

        assertFalse(signals.isEmpty(), "推动腿确认且价在0.618上方，应挂出限价回踩单");
        StrategySignal sig = signals.getLast();
        assertEquals("LIMIT", sig.orderType(), "新范式应挂限价单(maker)而非市价追单");
        assertEquals("LONG", sig.side());
        assertTrue(sig.isLong());
        assertEquals(0, sig.entryRefPrice().compareTo(new BigDecimal("185.434")),
                "挂单价应=0.618回撤位 301-187*0.618");
        assertEquals(0, sig.takeProfitPrice().compareTo(new BigDecimal("301")),
                "止盈=1.0延伸位=前高301");
        assertTrue(sig.stopLossPrice().compareTo(sig.entryRefPrice()) < 0, "止损应在挂单价下方");
        assertTrue(sig.reason().contains("黄金口袋"));
    }

    @Test
    void noLimitSignalWhenPriceDipsBelowGoldenPocket() {
        FiboRetracementStrategy strategy = new FiboRetracementStrategy(fast(), List.of(SYM));
        WindowedMarketView view = new WindowedMarketView(20_000);
        List<StrategySignal> signals = new ArrayList<>();

        feedRising(strategy, view, signals, 100, 40, 5);
        feed(strategy, view, 300, 301, 218, 220).ifPresent(signals::add);  // 前置：浅回撤已挂单
        assertFalse(signals.isEmpty(), "前置：浅回撤时应已挂出限价单");
        int before = signals.size();

        // 价跌破0.618位(185.4)到170：限价是被动maker，现价已穿过挂单位则不再发信号(否则变市价成交)
        Optional<StrategySignal> dipped = feed(strategy, view, 220, 221, 168, 170);

        assertTrue(dipped.isEmpty(), "现价跌破0.618挂单位后，不应再发限价信号(守住maker被动成交)");
        assertEquals(before, signals.size());
    }

    // ---- T1: 高周期均线多头排列门 (maAligned) ----

    @Test
    void maAlignedTrueForRisingSeriesUpLegOnly() {
        List<KlineBar> htf = buildHtf(220, true);   // 单调上行
        assertTrue(FiboRetracementStrategy.maAligned(htf, true), "上行序列应判为多头排列(放行做多腿)");
        assertFalse(FiboRetracementStrategy.maAligned(htf, false), "上行序列不应判为空头排列");
    }

    @Test
    void maAlignedTrueForFallingSeriesDownLegOnly() {
        List<KlineBar> htf = buildHtf(220, false);  // 单调下行
        assertTrue(FiboRetracementStrategy.maAligned(htf, false), "下行序列应判为空头排列(放行做空腿)");
        assertFalse(FiboRetracementStrategy.maAligned(htf, true), "下行序列不应判为多头排列");
    }

    @Test
    void maAlignedFalseWhenInsufficientBars() {
        List<KlineBar> htf = buildHtf(150, true);   // <200 根，算不出 SMA200
        assertFalse(FiboRetracementStrategy.maAligned(htf, true), "不足200根应保守判否");
        assertFalse(FiboRetracementStrategy.maAligned(htf, false), "不足200根应保守判否");
    }

    // ---- 趋势闸组合 (trendGate = 基础顺势 + 可选T1 均线多头排列) ----

    @Test
    void trendGateBaseRespectsDirection() {
        List<KlineBar> up = buildHtf(230, true);
        assertTrue(FiboRetracementStrategy.trendGate(up, true, false), "上行+做多腿: 基础门放行");
        assertFalse(FiboRetracementStrategy.trendGate(up, false, false), "上行+做空腿: 基础门拦截");
    }

    @Test
    void trendGateAlignPassesInCleanTrend() {
        List<KlineBar> up = buildHtf(230, true);
        assertTrue(FiboRetracementStrategy.trendGate(up, true, true), "干净上行: T1 放行做多");
        List<KlineBar> down = buildHtf(230, false);
        assertTrue(FiboRetracementStrategy.trendGate(down, false, true), "干净下行: T1 放行做空");
        assertFalse(FiboRetracementStrategy.trendGate(down, true, true), "下行+做多腿: 拦截");
    }

    /** 构造 n 根 1h bar：rising=单调上行收盘、否则单调下行。 */
    private static List<KlineBar> buildHtf(int n, boolean rising) {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double c = rising ? 100 + i : 100 + (n - i);
            long t = i * 3_600_000L;
            bars.add(new KlineBar(t, t + 3_600_000L - 1,
                    BigDecimal.valueOf(c), BigDecimal.valueOf(c + 1),
                    BigDecimal.valueOf(c - 1), BigDecimal.valueOf(c), BigDecimal.ONE));
        }
        return bars;
    }

    // ---- helpers ----

    /** 喂 count 根递增5m(每根+step)，形成上行推动段；信号收进 sink。 */
    private static void feedRising(FiboRetracementStrategy strategy, WindowedMarketView view,
                                   List<StrategySignal> sink, double startPx, int count, double step) {
        double px = startPx;
        for (int k = 0; k < count; k++) {
            double next = px + step;
            feed(strategy, view, px, next + 1, px - 1, next).ifPresent(sink::add);
            px = next;
        }
    }

    /** 直接喂一根5m(自动接续时间)，闭合后调用策略，返回本次信号。 */
    private static Optional<StrategySignal> feed(FiboRetracementStrategy strategy, WindowedMarketView view,
                                                 double open, double high, double low, double close) {
        long t = view.closedBars(M5, 1).isEmpty()
                ? 0
                : view.closedBars(M5, 1).getLast().openTime() + M5;
        view.append(new KlineBar(t, t + M5 - 1,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.ONE));
        return strategy.onBarClosed(SYM, view);
    }
}
