package com.mawai.wiibquant.agent.strategy.backtest;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.StrategyMarketView;
import com.mawai.wiibquant.agent.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyKlineBacktestEngineTest {

    private static final long M5 = 5 * 60_000L;
    private static final String SYM = "BTCUSDT";

    @Test
    void fillsAtNextBarOpenAndTakesProfit() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));
        bars.set(10, bar(10, 102, 103, 101, 102));
        bars.set(15, bar(15, 102, 131, 101, 128));

        BacktestResult result = new StrategyKlineBacktestEngine(
                onceLongAt(9, 90, 130), SYM, bars, new BigDecimal("100000"), 5, 0, null, null)
                .run();

        assertEquals(1, result.totalTrades());
        BacktestResult.Trade trade = result.getTrades().getFirst();
        assertEquals(0, trade.entryPrice().compareTo(bd(102)), "必须用下一根5m开盘价成交");
        assertEquals(0, trade.exitPrice().compareTo(bd(130)), "TP 触发价成交");
        assertTrue(trade.pnl().signum() > 0);
    }

    @Test
    void skipsEntryWhenOpenGapsBeyondStopLoss() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));
        bars.set(10, bar(10, 85, 86, 84, 85));

        BacktestResult result = new StrategyKlineBacktestEngine(
                onceLongAt(9, 90, 110), SYM, bars, new BigDecimal("100000"), 5, 0, null, null)
                .run();

        assertEquals(0, result.totalTrades());
    }

    @Test
    void keepsSinglePositionEvenWhenStrategyKeepsFiring() {
        List<KlineBar> bars = new ArrayList<>(flatBars(30, 100));

        BacktestResult result = new StrategyKlineBacktestEngine(
                alwaysLongAfter(9, 90, 200), SYM, bars, new BigDecimal("100000"), 5, 0, null, null)
                .run();

        assertEquals(1, result.totalTrades());
        assertEquals("FORCE_CLOSE", result.getTrades().getFirst().exitReason());
    }

    private static TradingStrategySpi onceLongAt(int signalBarIndex, double sl, double tp) {
        return new TradingStrategySpi() {
            private boolean fired;

            @Override
            public String id() {
                return "STUB";
            }

            @Override
            public List<String> symbols() {
                return List.of(SYM);
            }

            @Override
            public StrategyRiskPolicy riskPolicy() {
                return StrategyRiskPolicy.defaults();
            }

            @Override
            public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
                List<KlineBar> base = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, 100_000);
                if (!fired && base.size() == signalBarIndex + 1) {
                    fired = true;
                    return Optional.of(longSignal(base.getLast(), sl, tp));
                }
                return Optional.empty();
            }
        };
    }

    private static TradingStrategySpi alwaysLongAfter(int firstSignalBarIndex, double sl, double tp) {
        return new TradingStrategySpi() {
            @Override
            public String id() {
                return "STUB";
            }

            @Override
            public List<String> symbols() {
                return List.of(SYM);
            }

            @Override
            public StrategyRiskPolicy riskPolicy() {
                return StrategyRiskPolicy.defaults();
            }

            @Override
            public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
                List<KlineBar> base = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, 100_000);
                if (base.size() >= firstSignalBarIndex + 1) {
                    return Optional.of(longSignal(base.getLast(), sl, tp));
                }
                return Optional.empty();
            }
        };
    }

    private static StrategySignal longSignal(KlineBar bar, double sl, double tp) {
        return new StrategySignal("STUB", SYM, "LONG", true,
                bar.close(), bd(sl), bd(tp), 1.0, "stub", bar.closeTime(), "MARKET");
    }

    private static List<KlineBar> flatBars(int n, double price) {
        List<KlineBar> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(bar(i, price, price + 1, price - 1, price));
        }
        return out;
    }

    private static KlineBar bar(int i, double open, double high, double low, double close) {
        long t = i * M5;
        return new KlineBar(t, t + M5 - 1, bd(open), bd(high), bd(low), bd(close), BigDecimal.ONE);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    void limitFillsAtLimitPriceWhenTouched() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));   // low 恒 99，不触及 95
        bars.set(12, bar(12, 100, 101, 94, 100));                   // bar12 回踩到 94，触及挂单价 95
        BacktestResult result = new StrategyKlineBacktestEngine(
                limitLongFrom(9, 95, 90, 110), SYM, bars, new BigDecimal("100000"), 5, 0, null, null)
                .run();
        assertEquals(1, result.totalTrades());
        assertEquals(0, result.getTrades().getFirst().entryPrice().compareTo(bd(95)),
                "limit 必须成交于挂单价 95，而非下一根开盘价 100");
    }

    @Test
    void limitNotFilledWhenNeverTouched() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));   // low 恒 99，从不到 95
        BacktestResult result = new StrategyKlineBacktestEngine(
                limitLongFrom(9, 95, 90, 110), SYM, bars, new BigDecimal("100000"), 5, 0, null, null)
                .run();
        assertEquals(0, result.totalTrades());
    }

    @Test
    void limitCancelledWhenStrategyStopsReaffirming() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));
        bars.set(15, bar(15, 100, 101, 94, 100));                   // bar15 才回踩，但挂单 bar12 已撤
        BacktestResult result = new StrategyKlineBacktestEngine(
                limitLongBetween(9, 12, 95, 90, 110), SYM, bars, new BigDecimal("100000"), 5, 0, null, null)
                .run();
        assertEquals(0, result.totalTrades(), "reaffirm 停止后挂单撤销，后续触及不成交");
    }

    @Test
    void stopFillsAtTriggerWhenBrokenThrough() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));   // high 恒 101，够不到 105
        bars.set(12, bar(12, 100, 106, 99, 105));                   // bar12 冲高 106，突破触发价 105
        BacktestResult result = new StrategyKlineBacktestEngine(
                stopLongBetween(9, Integer.MAX_VALUE, 105, 95, 130), SYM, bars,
                new BigDecimal("100000"), 5, 0, null, null)
                .run();
        assertEquals(1, result.totalTrades());
        assertEquals(0, result.getTrades().getFirst().entryPrice().compareTo(bd(105)),
                "stop 必须成交于触发价 105，而非开盘/收盘价");
    }

    @Test
    void stopNotFilledWhenNeverBroken() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));   // high 恒 101，从不到 105
        BacktestResult result = new StrategyKlineBacktestEngine(
                stopLongBetween(9, Integer.MAX_VALUE, 105, 95, 130), SYM, bars,
                new BigDecimal("100000"), 5, 0, null, null)
                .run();
        assertEquals(0, result.totalTrades());
    }

    @Test
    void stopFillsAtOpenWhenGappedThrough() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));
        bars.set(12, bar(12, 108, 110, 107, 109));                  // 开盘 108 已跳空越过触发价 105
        BacktestResult result = new StrategyKlineBacktestEngine(
                stopLongBetween(9, Integer.MAX_VALUE, 105, 95, 130), SYM, bars,
                new BigDecimal("100000"), 5, 0, null, null)
                .run();
        assertEquals(1, result.totalTrades());
        assertEquals(0, result.getTrades().getFirst().entryPrice().compareTo(bd(108)),
                "跳空穿越必须按开盘劣价 108 成交，不许拿触发价 105");
    }

    @Test
    void stopCancelledWhenStrategyStopsReaffirming() {
        List<KlineBar> bars = new ArrayList<>(flatBars(20, 100));
        bars.set(15, bar(15, 100, 106, 99, 100));                   // bar15 才突破，但挂单 bar12 已撤
        BacktestResult result = new StrategyKlineBacktestEngine(
                stopLongBetween(9, 12, 105, 95, 130), SYM, bars,
                new BigDecimal("100000"), 5, 0, null, null)
                .run();
        assertEquals(0, result.totalTrades(), "reaffirm 停止后触价单撤销，后续突破不成交");
    }

    /** 仅在 bar 索引 [fromBar, toBarExclusive) 输出 STOP 信号，之外 empty（模拟信号窗口过期→撤单）。 */
    private static TradingStrategySpi stopLongBetween(int fromBar, int toBarExclusive,
                                                      double trigger, double sl, double tp) {
        return new TradingStrategySpi() {
            @Override public String id() { return "STUB"; }
            @Override public List<String> symbols() { return List.of(SYM); }
            @Override public StrategyRiskPolicy riskPolicy() { return StrategyRiskPolicy.defaults(); }
            @Override
            public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
                int idx = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, 100_000).size() - 1;
                if (idx >= fromBar && idx < toBarExclusive) {
                    return Optional.of(new StrategySignal("STUB", SYM, "LONG", true,
                            bd(trigger), bd(sl), bd(tp), 1.0, "stub", idx * M5 + M5 - 1, "STOP"));
                }
                return Optional.empty();
            }
        };
    }

    /** 从 fromBar 起每根持续输出 LIMIT 信号（reaffirm 不撤）。 */
    private static TradingStrategySpi limitLongFrom(int fromBar, double limit, double sl, double tp) {
        return limitLongBetween(fromBar, Integer.MAX_VALUE, limit, sl, tp);
    }

    /** 仅在 bar 索引 [fromBar, toBarExclusive) 输出 LIMIT 信号，之外 empty（模拟挂单失效→撤单）。 */
    private static TradingStrategySpi limitLongBetween(int fromBar, int toBarExclusive,
                                                       double limit, double sl, double tp) {
        return new TradingStrategySpi() {
            @Override public String id() { return "STUB"; }
            @Override public List<String> symbols() { return List.of(SYM); }
            @Override public StrategyRiskPolicy riskPolicy() { return StrategyRiskPolicy.defaults(); }
            @Override
            public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
                int idx = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, 100_000).size() - 1;
                if (idx >= fromBar && idx < toBarExclusive) {
                    return Optional.of(new StrategySignal("STUB", SYM, "LONG", true,
                            bd(limit), bd(sl), bd(tp), 1.0, "stub", idx * M5 + M5 - 1, "LIMIT"));
                }
                return Optional.empty();
            }
        };
    }
}
