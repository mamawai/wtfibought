package com.mawai.wiibservice.agent.trading.backtest;

import com.mawai.wiibcommon.enums.KlineInterval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BacktestComparisonResultTest {

    @Test
    void backtestResultCalculatesAverageR() {
        BacktestResult result = new BacktestResult(bd("100000"));
        result.addTrade(trade("BREAKOUT", "LONG", 10, 12, "100", "1.0", "TP"));
        result.addTrade(trade("BREAKOUT", "LONG", 20, 22, "-50", "-0.5", "SL"));
        result.addTrade(trade("MR", "SHORT", 30, 31, "30", null, "SIGNAL_CLOSE"));

        assertThat(result.avgR()).isEqualTo(0.25);
        assertThat(result.statsByStrategy("BREAKOUT").avgR()).isEqualTo(0.25);
        assertThat(result.statsByStrategy("MR").avgR()).isZero();
    }

    @Test
    void backtestResultIncludesMaSlopeStatsAndEquityCurve() {
        BacktestResult result = new BacktestResult(bd("100000"));
        result.addTrade(trade("MA_SLOPE", "LONG", 10, 16, "120", "1.2", "SIGNAL_CLOSE"));
        result.recordEquity(bd("100120"));

        assertThat(result.allStrategyStats())
                .extracting(BacktestResult.StrategyStats::strategy)
                .contains("MA_SLOPE");
        assertThat(result.statsByStrategy("MA_SLOPE").trades()).isEqualTo(1);
        assertThat(result.getEquityCurve()).containsExactly(bd("100000"), bd("100120"));
    }

    @Test
    void backtestTradingToolsRecordsReasonAwareCloseAndRoundTripFeePnl() {
        BacktestTradingTools tools = new BacktestTradingTools(bd("10000"), "BTCUSDT");
        tools.setCurrentPrice(bd("100"));
        tools.setCurrentBarIndex(10);

        assertThat(tools.openPositionWithResult("LONG", bd("1"), 10,
                "MARKET", null, bd("90"), bd("120"), "MA_SLOPE").success()).isTrue();
        tools.markOpenBarIndex(10);

        tools.setCurrentBarIndex(12);
        tools.setCurrentPrice(bd("110"));
        tools.closePositionWithReason(1L, bd("1"), "MA_SLOPE_SIGNAL_REVERSE state=STRONG_DOWN");

        BacktestTradingTools.ClosedTrade trade = tools.getClosedTrades().getFirst();
        assertThat(trade.exitReason()).isEqualTo("MA_SLOPE_SIGNAL_REVERSE");
        assertThat(trade.pnl()).isEqualByComparingTo("9.91600000");
        assertThat(trade.fee()).isEqualByComparingTo("0.08400000");
    }

    @Test
    void aggregateAlignsHigherTimeframeToExchangeOpenTime() {
        List<BigDecimal[]> bars = new ArrayList<>();
        for (int minute = 6; minute < 51; minute += 3) {
            bars.add(kline(minute));
        }

        List<BigDecimal[]> aggregated = BacktestEngine.aggregate(bars, 5, KlineInterval.M15);

        assertThat(aggregated).hasSize(2);
        assertThat(aggregated.getFirst()[5]).isEqualByComparingTo("900000");
        assertThat(aggregated.getFirst()[6]).isEqualByComparingTo("1799999");
        assertThat(aggregated.getFirst()[2]).isEqualByComparingTo("27");
        assertThat(aggregated.get(1)[5]).isEqualByComparingTo("1800000");
        assertThat(aggregated.get(1)[2]).isEqualByComparingTo("42");
    }

    @Test
    void comparesMatchedAndUnmatchedTrades() {
        BacktestResult legacy = new BacktestResult(bd("100000"));
        legacy.addTrade(trade("BREAKOUT", "LONG", 10, 12, "-100", "-1.0", "SL"));
        legacy.addTrade(trade("BREAKOUT", "LONG", 20, 25, "200", "2.0", "TP"));
        legacy.addTrade(trade("MR", "LONG", 30, 36, "50", "0.5", "SIGNAL_CLOSE"));
        legacy.addTrade(trade("LEGACY_TREND", "SHORT", 40, 50, "100", "1.0", "SIGNAL_CLOSE"));
        legacy.addTrade(trade("MR", "SHORT", 70, 74, "-20", "-0.2", "SL"));

        BacktestResult playbook = new BacktestResult(bd("100000"));
        playbook.addTrade(trade("BREAKOUT", "LONG", 10, 11, "-80", "-0.8", "SIGNAL_CLOSE"));
        playbook.addTrade(trade("BREAKOUT", "LONG", 20, 30, "350", "3.5", "TRAIL"));
        playbook.addTrade(trade("MR", "LONG", 30, 33, "40", "0.4", "SIGNAL_CLOSE"));
        playbook.addTrade(trade("LEGACY_TREND", "SHORT", 40, 55, "150", "1.5", "TRAIL"));
        playbook.addTrade(trade("BREAKOUT", "SHORT", 80, 84, "60", "0.6", "TP"));

        BacktestComparisonResult report =
                BacktestComparisonResult.compare("BTCUSDT", "TEST", legacy, playbook);

        assertThat(report.overallDelta().totalTradesDelta()).isZero();
        assertThat(report.overallDelta().netProfitDelta()).isEqualByComparingTo("290");
        assertThat(report.tradeDiffSummary().matchedTrades()).isEqualTo(4);
        assertThat(report.tradeDiffSummary().legacyOnlyTrades()).isEqualTo(1);
        assertThat(report.tradeDiffSummary().playbookOnlyTrades()).isEqualTo(1);
        assertThat(report.tradeDiffSummary().playbookEarlierTrades()).isEqualTo(2);
        assertThat(report.tradeDiffSummary().playbookLaterTrades()).isEqualTo(2);
        assertThat(report.tradeDiffSummary().breakoutLosingPnlDelta()).isEqualByComparingTo("20");
        assertThat(report.tradeDiffSummary().breakoutRunnerImprovedTrades()).isEqualTo(1);
        assertThat(report.tradeDiffs()).extracting(BacktestComparisonResult.TradeDiff::status)
                .contains("MATCHED", "LEGACY_ONLY", "PLAYBOOK_ONLY");
    }

    @Test
    void comparesPlaybookPartialCloseAsOneLifecycleTrade() {
        BacktestResult legacy = new BacktestResult(bd("100000"));
        legacy.addTrade(trade("BREAKOUT", "LONG", 10, 30, "400", "4.0", "TP"));

        BacktestResult playbook = new BacktestResult(bd("100000"));
        playbook.addTrade(trade("BREAKOUT", "LONG", 10, 20, "100", "2.0", "PARTIAL", "0.3"));
        playbook.addTrade(trade("BREAKOUT", "LONG", 10, 40, "350", "5.0", "TP", "0.7"));

        BacktestComparisonResult report =
                BacktestComparisonResult.compare("BTCUSDT", "TEST", legacy, playbook);

        assertThat(report.overallDelta().totalTradesDelta()).isZero();
        assertThat(report.tradeDiffSummary().matchedTrades()).isEqualTo(1);
        assertThat(report.tradeDiffSummary().playbookOnlyTrades()).isZero();
        assertThat(report.tradeDiffSummary().avgCloseBarDelta()).isEqualTo(10);

        BacktestComparisonResult.TradeDiff diff = report.tradeDiffs().getFirst();
        assertThat(diff.playbookPnl()).isEqualByComparingTo("450");
        assertThat(diff.rDelta()).isCloseTo(0.1, within(0.000001));
        assertThat(diff.playbookExitReason()).isEqualTo("PARTIAL+TP");
    }

    private static BacktestResult.Trade trade(String strategy, String side,
                                              int openBar, int closeBar,
                                              String pnl, String rMultiple,
                                              String reason) {
        return trade(strategy, side, openBar, closeBar, pnl, rMultiple, reason, "1");
    }

    private static BacktestResult.Trade trade(String strategy, String side,
                                              int openBar, int closeBar,
                                              String pnl, String rMultiple,
                                              String reason,
                                              String quantity) {
        return new BacktestResult.Trade(
                openBar,
                closeBar,
                side,
                strategy,
                bd("100"),
                bd("110"),
                bd(quantity),
                10,
                bd(pnl),
                BigDecimal.ZERO,
                rMultiple != null ? bd(rMultiple) : null,
                reason
        );
    }

    private static BigDecimal[] kline(int minute) {
        BigDecimal price = bd(String.valueOf(minute));
        BigDecimal openTime = BigDecimal.valueOf(minute * 60_000L);
        BigDecimal closeTime = BigDecimal.valueOf((minute + 3) * 60_000L - 1);
        return new BigDecimal[]{
                price.add(BigDecimal.ONE),
                price.subtract(BigDecimal.ONE),
                price,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                openTime,
                closeTime
        };
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
