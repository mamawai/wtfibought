package com.mawai.wiibquant.agent.strategy.liq;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.backtest.BacktestResult;
import com.mawai.wiibquant.agent.strategy.backtest.StrategyKlineBacktestEngine;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.WindowedMarketView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LiqFadeStrategyTest {

    private static final long M5 = 5 * 60_000L;
    private static final String SYM = "ETHUSDT";

    /** 测试参数：阈值取整便于控盘（flush≤-1.5%、prem≤-10bp、sell≥0.65）；hold=48 仅为集成测试确定性，与生产默认(12)无关。 */
    private static LiqFadeParams testParams() {
        return new LiqFadeParams(3, 12, 2, 48, 0.08, 0.10, 10 * 60_000L,
                Map.of(SYM, new LiqFadeParams.CoinGate(-0.015, -0.001, 0.65)));
    }

    /** 可控 side data 桩：premium 恒值（默认 NaN=缺），taker 按桶查表（缺=NaN）。 */
    private static final class StubSide implements LiqSideData {
        double prem = Double.NaN;
        final Map<Long, Double> taker = new HashMap<>();

        @Override
        public double takerBuy(String symbol, long bucketOpenMs) {
            return taker.getOrDefault(bucketOpenMs, Double.NaN);
        }

        @Override
        public double premiumAt(String symbol, long atMs) {
            return prem;
        }
    }

    private static KlineBar bar(int i, double open, double close, double vol) {
        double hi = Math.max(open, close) + 0.2, lo = Math.min(open, close) - 0.2;
        return new KlineBar(i * M5, (i + 1) * M5 - 1,
                BigDecimal.valueOf(open), BigDecimal.valueOf(hi), BigDecimal.valueOf(lo),
                BigDecimal.valueOf(close), BigDecimal.valueOf(vol));
    }

    private static Optional<StrategySignal> feed(LiqFadeStrategy s, WindowedMarketView v, int i, double close) {
        v.append(bar(i, close, close, 10));
        return s.onBarClosed(SYM, v);
    }

    @Test
    void 瀑布加premium错位_双签名触发市价多单_且同bar重入幂等() {
        StubSide side = new StubSide();
        side.prem = -0.002;
        LiqFadeStrategy s = new LiqFadeStrategy(testParams(), List.of(SYM), side);
        WindowedMarketView view = new WindowedMarketView(1000);
        for (int i = 0; i < 40; i++) {
            assertThat(feed(s, view, i, 100)).as("平盘仅 prem 单签名不触发").isEmpty();
        }
        assertThat(feed(s, view, 40, 99.5)).isEmpty();
        assertThat(feed(s, view, 41, 99.0)).as("15m跌1%未过线").isEmpty();

        Optional<StrategySignal> sig = feed(s, view, 42, 98.0);   // 98/100-1=-2% + prem → flags=2
        assertThat(sig).isPresent();
        StrategySignal g = sig.get();
        assertThat(g.orderType()).isEqualTo("MARKET");
        assertThat(g.side()).isEqualTo("LONG");
        assertThat(g.isLong()).isTrue();
        assertThat(g.entryRefPrice().compareTo(new BigDecimal("98"))).isZero();
        assertThat(g.stopLossPrice().compareTo(new BigDecimal("90.16"))).isZero();     // 98×0.92
        assertThat(g.takeProfitPrice().compareTo(new BigDecimal("107.8"))).isZero();   // 98×1.10
        assertThat(g.reason()).contains("LiqFade");

        Optional<StrategySignal> again = s.onBarClosed(SYM, view);   // 同 bar 重入
        assertThat(again).isPresent();
        assertThat(again.get().entryRefPrice().compareTo(g.entryRefPrice())).isZero();
    }

    @Test
    void 缺side数据只剩瀑布单签名_保守不触发() {
        StubSide side = new StubSide();   // prem=NaN, taker 全缺
        LiqFadeStrategy s = new LiqFadeStrategy(testParams(), List.of(SYM), side);
        WindowedMarketView view = new WindowedMarketView(1000);
        for (int i = 0; i < 40; i++) feed(s, view, i, 100);
        feed(s, view, 40, 99.5);
        feed(s, view, 41, 99.0);
        assertThat(feed(s, view, 42, 98.0)).as("缺 prem/taker 时 flush 单签名不够 minFlags").isEmpty();
    }

    @Test
    void 冷却窗内不重复触发_满窗后可再触发() {
        StubSide side = new StubSide();
        side.prem = -0.002;
        LiqFadeStrategy s = new LiqFadeStrategy(testParams(), List.of(SYM), side);
        WindowedMarketView view = new WindowedMarketView(1000);
        for (int i = 0; i < 40; i++) feed(s, view, i, 100);
        feed(s, view, 40, 99.5);
        feed(s, view, 41, 99.0);
        assertThat(feed(s, view, 42, 98.0)).isPresent();

        double px = 98.0;
        for (int i = 43; i <= 53; i++) {                     // 每根再跌0.7%, 瀑布条件持续成立
            px *= 0.993;
            assertThat(feed(s, view, i, px)).as("冷却期内 bar " + i).isEmpty();
        }
        px *= 0.993;
        assertThat(feed(s, view, 54, px)).as("冷却 12 根期满应可再触发").isPresent();
    }

    @Test
    void 引擎集成_持仓满48根按TIME_EXIT收盘价平仓() {
        StubSide side = new StubSide();
        side.prem = -0.002;
        LiqFadeStrategy s = new LiqFadeStrategy(testParams(), List.of(SYM), side);
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < 10; i++) bars.add(bar(i, 100, 100, 10));
        bars.add(bar(10, 100, 99.5, 10));
        bars.add(bar(11, 99.5, 99.0, 10));
        bars.add(bar(12, 99.0, 98.0, 10));                   // bar12 收盘触发, bar13 开盘98成交
        for (int i = 13; i < 120; i++) bars.add(bar(i, 98, 98, 10));

        BacktestResult r = new StrategyKlineBacktestEngine(
                s, SYM, bars, new BigDecimal("100000"), 5, 0, null, null).run();

        assertThat(r.getTrades()).hasSize(1);
        BacktestResult.Trade t = r.getTrades().getFirst();
        assertThat(t.side()).isEqualTo("LONG");
        assertThat(t.exitReason()).isEqualTo("TIME_EXIT");
        assertThat(t.entryPrice().compareTo(new BigDecimal("98"))).isZero();
        assertThat(t.closeBarIndex() - t.barIndex()).isEqualTo(48);   // 4h=48根整平仓
    }
}
