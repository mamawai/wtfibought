package com.mawai.wiibquant.agent.strategy.fibo;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.WindowedMarketView;
import com.mawai.wiibquant.agent.strategy.backtest.BacktestTradingTools;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
     * 策略已是方向无关纯回踩、无方向门；信号发射/挂单逻辑在此独立验证，整体效果由 FiboStrategyDbRun 在真实数据上验证。
     */
    private static FiboParams fast() {
        return new FiboParams(
                M5,                    // swing=5m
                3, 1.0, 2.0,           // atrPeriod, reversalAtrMult, minLegAtrMult
                0.618, 0.786, 0.786,   // entryFib, invalidationRatio, slFibRatio
                0.1, 1.0,              // slBufferAtrMult, tpExtensionRatio
                100_000, 500,          // orderTimeoutBars(极大), swingLookbackBars
                false, 0.5, 1.0,       // scaleOutOn, scaleOutFraction, scaleOutAtR
                0.0);                  // tpRMultiple(0=用延伸位)
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

    // ---- ST3: onPositionOpened 注册分批止盈 ----

    @Test
    void onPositionOpenedRegistersScaleOutAtOneR() {
        FiboParams params = fast().withScaleOut(true);  // scaleOutOn, fraction0.5, atR1.0
        FiboRetracementStrategy strategy = new FiboRetracementStrategy(params, List.of(SYM));
        BacktestTradingTools tools = new BacktestTradingTools(new BigDecimal("100000"), SYM);
        tools.setCurrentTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        tools.setCurrentPrice(new BigDecimal("150"));
        // 入场150、SL140(risk=10)、qty=2 → +1R 触发价=160、落袋=0.5×2=1
        Long posId = tools.openPositionWithResult("LONG", new BigDecimal("2"), 1, "MARKET",
                null, new BigDecimal("140"), new BigDecimal("170"), "FIBO").positionId();
        StrategySignal sig = new StrategySignal("FIBO", SYM, "LONG", true,
                new BigDecimal("150"), new BigDecimal("140"), new BigDecimal("170"),
                1.0, "test", 0, "LIMIT");
        strategy.onPositionOpened(SYM, sig, posId, new BigDecimal("150"), null, tools);

        tools.setCurrentBarIndex(1);
        tools.tickBar(new BigDecimal("160"), new BigDecimal("150"), new BigDecimal("158"), 1); // 价到 +1R

        var trade = tools.getClosedTrades().getFirst();
        assertEquals("SCALE", trade.exitReason());
        assertEquals(0, trade.exitPrice().compareTo(new BigDecimal("160")), "落袋触发价=入场+1R=160");
        assertEquals(0, trade.quantity().compareTo(new BigDecimal("1")), "落袋量=0.5×2=1");
        assertEquals(0, tools.getOpenPositions(SYM).getFirst().getQuantity().compareTo(new BigDecimal("1")),
                "剩余仓续存");
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
