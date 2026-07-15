package com.mawai.wiibquant.agent.strategy.turtle;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.backtest.BacktestResult;
import com.mawai.wiibquant.agent.strategy.backtest.StrategyKlineBacktestEngine;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.WindowedMarketView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TurtleStrategyTest {

    private static final long M5 = 5 * 60_000L;
    private static final String SYM = "BTCUSDT";
    private static final TurtleParams TEST = new TurtleParams(M5, 3, 2, 2, 10.0, false);

    private static KlineBar bar(int i, double open, double high, double low, double close) {
        return new KlineBar(i * M5, (i + 1) * M5 - 1,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high), BigDecimal.valueOf(low),
                BigDecimal.valueOf(close), BigDecimal.ONE);
    }

    @Test
    void 入场通道排除当前桶_向上和向下突破对称触发() {
        TurtleStrategy strategy = new TurtleStrategy(TEST, List.of(SYM));
        WindowedMarketView longView = new WindowedMarketView(100);
        longView.append(bar(0, 100, 101, 99, 100));
        longView.append(bar(1, 100, 102, 99, 101));
        longView.append(bar(2, 101, 103, 100, 102));
        assertThat(strategy.onBarClosed(SYM, longView)).isEmpty();
        longView.append(bar(3, 102, 105, 101, 104));

        Optional<StrategySignal> longSignal = strategy.onBarClosed(SYM, longView);
        assertThat(longSignal).isPresent();
        assertThat(longSignal.get().side()).isEqualTo("LONG");
        assertThat(longSignal.get().takeProfitPrice()).isNull();
        assertThat(longSignal.get().reason()).contains("channel=103");

        WindowedMarketView shortView = new WindowedMarketView(100);
        shortView.append(bar(0, 100, 101, 99, 100));
        shortView.append(bar(1, 100, 101, 98, 99));
        shortView.append(bar(2, 99, 100, 97, 98));
        shortView.append(bar(3, 98, 99, 94, 95));

        Optional<StrategySignal> shortSignal = strategy.onBarClosed(SYM, shortView);
        assertThat(shortSignal).isPresent();
        assertThat(shortSignal.get().side()).isEqualTo("SHORT");
        assertThat(shortSignal.get().reason()).contains("channel=97");
    }

    @Test
    void 引擎支持无固定止盈_多头跌破退出通道按CHANNEL_EXIT平仓() {
        TurtleStrategy strategy = new TurtleStrategy(TEST, List.of(SYM));
        List<KlineBar> bars = new ArrayList<>();
        bars.add(bar(0, 100, 101, 99, 100));
        bars.add(bar(1, 100, 102, 99, 101));
        bars.add(bar(2, 101, 103, 100, 102));
        bars.add(bar(3, 102, 105, 101, 104));   // 突破，下一根开盘成交
        bars.add(bar(4, 104, 106, 103, 105));
        bars.add(bar(5, 105, 107, 104, 106));
        bars.add(bar(6, 106, 106, 89, 90));     // 闭合价跌破此前2桶最低价

        BacktestResult result = new StrategyKlineBacktestEngine(
                strategy, SYM, bars, new BigDecimal("100000"), 5, 0, null, null).run();

        assertThat(result.getTrades()).hasSize(1);
        BacktestResult.Trade trade = result.getTrades().getFirst();
        assertThat(trade.side()).isEqualTo("LONG");
        assertThat(trade.entryPrice().compareTo(new BigDecimal("104"))).isZero();
        assertThat(trade.exitPrice().compareTo(new BigDecimal("90"))).isZero();
        assertThat(trade.exitReason()).isEqualTo("CHANNEL_EXIT");
    }

    @Test
    void 多头跌破2ATR灾难止损_按SL价平仓() {
        TurtleStrategy strategy = new TurtleStrategy(TEST, List.of(SYM));
        List<KlineBar> bars = new ArrayList<>();
        bars.add(bar(0, 100, 101, 99, 100));
        bars.add(bar(1, 100, 102, 99, 101));
        bars.add(bar(2, 101, 103, 100, 102));
        bars.add(bar(3, 102, 105, 101, 104));   // 突破，下一根开盘 104 成交；signal时 ATR=3.5，止损=104-3.5×10=69
        bars.add(bar(4, 104, 106, 103, 105));
        bars.add(bar(5, 105, 106, 60, 65));     // 盘中最低 60 击穿止损 69，按止损价成交（先于通道出场）

        BacktestResult result = new StrategyKlineBacktestEngine(
                strategy, SYM, bars, new BigDecimal("100000"), 5, 0, null, null).run();

        assertThat(result.getTrades()).hasSize(1);
        BacktestResult.Trade trade = result.getTrades().getFirst();
        assertThat(trade.side()).isEqualTo("LONG");
        assertThat(trade.entryPrice().compareTo(new BigDecimal("104"))).isZero();
        assertThat(trade.exitPrice().compareTo(new BigDecimal("69"))).isZero();  // 成交在止损价，非当根最低
        assertThat(trade.exitReason()).isEqualTo("SL");
    }
}
