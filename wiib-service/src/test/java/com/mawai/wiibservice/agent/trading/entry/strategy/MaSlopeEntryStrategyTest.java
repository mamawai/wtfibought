package com.mawai.wiibservice.agent.trading.entry.strategy;

import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyCandidate;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyResult;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MA_SLOPE;
import static org.assertj.core.api.Assertions.assertThat;

class MaSlopeEntryStrategyTest {

    private final MaSlopeEntryStrategy strategy = new MaSlopeEntryStrategy();

    @AfterEach
    void resetSymbols() {
        MaSlopeEntryStrategy.setEnabledSymbols(null);
    }

    @Test
    void acceptsStrongUpWithSameDirectionConfirm() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "110");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.rejectReason()).isNull();
        EntryStrategyCandidate candidate = result.candidate();
        assertThat(candidate.path()).isEqualTo(PATH_MA_SLOPE);
        assertThat(candidate.side()).isEqualTo("LONG");
        assertThat(candidate.maxLeverage()).isEqualTo(10);
        assertThat(candidate.positionScale()).isEqualTo(0.75);
        assertThat(candidate.reason()).contains("state=UP_ACCELERATING", "confirm=UP_ACCELERATING");
    }

    @Test
    void flatMa7SlopeOnlyExtinguishesLongAndDoesNotOpenCandidate() {
        MaSlopeStateClassifier.MaState state = MaSlopeStateClassifier.classify(
                bdList("100", "101", "102", "103", "103", "103", "103", "103"),
                bdList("98", "98.5", "99", "99.5", "100", "100.5", "101", "101.5"),
                bd("10"),
                bd("104"));

        assertThat(state.state()).isEqualTo(MaSlopeStateClassifier.MaSignalState.UP_DECELERATING);
        assertThat(state.hasCandidate()).isFalse();
    }

    @Test
    void rejectsSymbolOutsideMaSlopeWhitelist() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "110");

        EntryStrategyResult result = strategy.build(context("DOGEUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_SYMBOL_NOT_ENABLED");
    }

    @Test
    void rejectsStrongOppositeConfirmTimeframe() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                downMa7(), downMa25(),
                "110");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_CONFIRM_AGAINST");
    }

    @Test
    void rejectsWhenPriceTooFarFromMa25() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "200");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_TOO_EXTENDED");
    }

    @Test
    void rejectsWhenConfirmOnlyHasLiveAtr() {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "regime": "TREND_UP",
                  "atr": 10,
                  "qualityFlags": [],
                  "indicatorsByTimeframe": {
                    "5m": {
                      "ma7": 107,
                      "ma25": 101.5,
                      "ma7_series_closed": [100,101,102,103,104,105,106,107,108],
                      "ma25_series_closed": [98,98.5,99,99.5,100,100.5,101,101.5,102],
                      "adx": 26,
                      "atr14_closed": 10,
                      "atr_series_closed": [9,10],
                      "atr_mean_30_closed": 9,
                      "atr_spike_ratio": 1.1
                    },
                    "15m": {
                      "ma7_series_closed": [100,101,102,103,104,105,106,107],
                      "ma25_series_closed": [98,98.5,99,99.5,100,100.5,101,101.5],
                      "atr14": 12,
                      "ma_alignment": 1
                    },
                    "1h": {"ma_alignment": 1}
                  }
                }
                """);
        MarketContext ctx = MarketContext.parse(forecast, bd("110"), KlineInterval.M5);

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_DATA_MISSING");
    }

    @Test
    void rejectsWhenSingleBarTriggerNeedsTwoClosedBarsConfirm() {
        MarketContext ctx = marketContext(
                bdList("100", "100", "100", "100", "100", "100", "100", "100", "104"),
                bdList("100", "100", "100", "100", "100", "100.1", "100.2", "100.3", "100.4"),
                upMa7(), upMa25(),
                "104.5");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_NEED_2BAR_CONFIRM");
    }

    @Test
    void previousPrimaryUsesPreviousClosedAtrInsteadOfCurrentSpikeAtr() {
        MarketContext ctx = marketContextWithAtrSeries(
                upMa7(), upMa25(), List.of(bd("10"), bd("100")));

        MaSlopeStateClassifier.MaState previous = MaSlopeStateClassifier.classifyPreviousPrimary(ctx);

        assertThat(previous.sameStrongDirection(true)).isTrue();
    }

    @Test
    void acceptsM15WhenOneHourConfirmDataIsAvailable() {
        MarketContext ctx = m15MarketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "110");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", KlineInterval.M15, ctx));

        assertThat(result.rejectReason()).isNull();
        assertThat(result.candidate()).isNotNull();
        assertThat(result.candidate().side()).isEqualTo("LONG");
    }

    @Test
    void linearRegressionSlopeKeepsExpectedSign() {
        assertThat(MaSlopeStateClassifier.linearRegressionSlope(bdList("1", "2", "3", "4", "5")))
                .isPositive();
        assertThat(MaSlopeStateClassifier.linearRegressionSlope(bdList("5", "4", "3", "2", "1")))
                .isNegative();
        assertThat(MaSlopeStateClassifier.linearRegressionSlope(bdList("3", "3", "3", "3", "3")))
                .isZero();
    }

    private static EntryStrategyContext context(String symbol, MarketContext ctx) {
        return new EntryStrategyContext(symbol, KlineInterval.M5, ctx, profile(), "AUTO", false, 0.75);
    }

    private static EntryStrategyContext context(String symbol, KlineInterval interval, MarketContext ctx) {
        return new EntryStrategyContext(symbol, interval, ctx, profile(), "AUTO", false, 0.75);
    }

    private static MarketContext marketContext(List<BigDecimal> primaryMa7,
                                               List<BigDecimal> primaryMa25,
                                               List<BigDecimal> confirmMa7,
                                               List<BigDecimal> confirmMa25,
                                               String price) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "regime": "TREND_UP",
                  "atr": 10,
                  "fundingRateExtreme": 0.0,
                  "qualityFlags": [],
                  "bidAskImbalance": 0.1,
                  "takerBuySellPressure": 0.1,
                  "indicatorsByTimeframe": {
                    "5m": {
                      "ma7": %s,
                      "ma25": %s,
                      "ma7_series_closed": %s,
                      "ma25_series_closed": %s,
                      "adx": 26,
                      "plus_di": 30,
                      "minus_di": 12,
                      "atr14_closed": 10,
                      "atr_series_closed": [9,10],
                      "atr_mean_30_closed": 9,
                      "atr_spike_ratio": 1.1,
                      "macd_cross": "golden",
                      "macd_hist_trend": "rising_5",
                      "macd_dif": 2,
                      "macd_dea": 1,
                      "ema20": 100,
                      "ma_alignment": 1,
                      "close_trend": "rising_5",
                      "boll_expanding_5": true,
                      "volume_ratio_recent_5_closed": [1.2, 1.3, 1.4, 1.3, 1.2]
                    },
                    "15m": {
                      "ma7": %s,
                      "ma25": %s,
                      "ma7_series_closed": %s,
                      "ma25_series_closed": %s,
                      "atr14_closed": 12,
                      "ma_alignment": 1
                    },
                    "1h": {"ma_alignment": 1}
                  }
                }
                """.formatted(
                primaryMa7.getLast().toPlainString(),
                primaryMa25.getLast().toPlainString(),
                array(primaryMa7),
                array(primaryMa25),
                confirmMa7.getLast().toPlainString(),
                confirmMa25.getLast().toPlainString(),
                array(confirmMa7),
                array(confirmMa25)));
        return MarketContext.parse(forecast, bd(price), KlineInterval.M5);
    }

    private static MarketContext m15MarketContext(List<BigDecimal> primaryMa7,
                                                  List<BigDecimal> primaryMa25,
                                                  List<BigDecimal> confirmMa7,
                                                  List<BigDecimal> confirmMa25,
                                                  String price) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "regime": "TREND_UP",
                  "atr": 10,
                  "fundingRateExtreme": 0.0,
                  "qualityFlags": [],
                  "bidAskImbalance": 0.1,
                  "takerBuySellPressure": 0.1,
                  "indicatorsByTimeframe": {
                    "15m": {
                      "ma7": %s,
                      "ma25": %s,
                      "ma7_series_closed": %s,
                      "ma25_series_closed": %s,
                      "adx": 22,
                      "plus_di": 30,
                      "minus_di": 12,
                      "atr14_closed": 10,
                      "atr_series_closed": [9,10],
                      "atr_mean_30_closed": 9,
                      "atr_spike_ratio": 1.1,
                      "macd_cross": "golden",
                      "macd_hist_trend": "rising_5",
                      "macd_dif": 2,
                      "macd_dea": 1,
                      "ema20": 100,
                      "ma_alignment": 1,
                      "close_trend": "rising_5",
                      "boll_expanding_5": true,
                      "volume_ratio_recent_5_closed": [1.2, 1.3, 1.4, 1.3, 1.2]
                    },
                    "1h": {
                      "ma7": %s,
                      "ma25": %s,
                      "ma7_series_closed": %s,
                      "ma25_series_closed": %s,
                      "atr14_closed": 12,
                      "ma_alignment": 1
                    }
                  }
                }
                """.formatted(
                primaryMa7.getLast().toPlainString(),
                primaryMa25.getLast().toPlainString(),
                array(primaryMa7),
                array(primaryMa25),
                confirmMa7.getLast().toPlainString(),
                confirmMa25.getLast().toPlainString(),
                array(confirmMa7),
                array(confirmMa25)));
        return MarketContext.parse(forecast, bd(price), KlineInterval.M15);
    }

    private static MarketContext marketContextWithAtrSeries(List<BigDecimal> primaryMa7,
                                                            List<BigDecimal> primaryMa25,
                                                            List<BigDecimal> atrSeriesClosed) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "regime": "TREND_UP",
                  "atr": 100,
                  "indicatorsByTimeframe": {
                    "5m": {
                      "ma7_series_closed": %s,
                      "ma25_series_closed": %s,
                      "atr14_closed": 100,
                      "atr_series_closed": %s,
                      "adx": 26,
                      "atr_mean_30_closed": 9,
                      "atr_spike_ratio": 1.1
                    },
                    "15m": {
                      "ma7_series_closed": %s,
                      "ma25_series_closed": %s,
                      "atr14_closed": 12,
                      "ma_alignment": 1
                    },
                    "1h": {"ma_alignment": 1}
                  }
                }
                """.formatted(array(primaryMa7), array(primaryMa25), array(atrSeriesClosed),
                array(primaryMa7), array(primaryMa25)));
        return MarketContext.parse(forecast, bd("110"), KlineInterval.M5);
    }

    private static List<BigDecimal> upMa7() {
        return bdList("100", "101", "102", "103", "104", "105", "106", "107", "108");
    }

    private static List<BigDecimal> upMa25() {
        return bdList("98", "98.5", "99", "99.5", "100", "100.5", "101", "101.5", "102");
    }

    private static List<BigDecimal> downMa7() {
        return bdList("108", "107", "106", "105", "104", "103", "102", "101", "100");
    }

    private static List<BigDecimal> downMa25() {
        return bdList("109.5", "109", "108.5", "108", "107.5", "107", "106.5", "106", "105.5");
    }

    private static SymbolProfile profile() {
        return new SymbolProfile(
                2.0, 4.0, 2.0, 2.0, 3.0, 2.0, 3.0,
                0.5, 1.5, 0.001, 0.12, 1.5, 0.8,
                5.0, 5.0);
    }

    private static List<BigDecimal> bdList(String... values) {
        return java.util.Arrays.stream(values).map(MaSlopeEntryStrategyTest::bd).toList();
    }

    private static String array(List<BigDecimal> values) {
        return values.stream().map(BigDecimal::toPlainString).toList().toString();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
