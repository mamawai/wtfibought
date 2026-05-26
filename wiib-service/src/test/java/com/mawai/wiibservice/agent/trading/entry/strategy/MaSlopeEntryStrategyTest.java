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
        assertThat(candidate.maxLeverage()).isEqualTo(50);
        assertThat(candidate.positionScale()).isEqualTo(0.75);
        assertThat(candidate.entryMode()).isEqualTo("LAUNCH");
        assertThat(candidate.wasLateContinuation()).isFalse();
        assertThat(candidate.reason()).contains("entryMode=LAUNCH", "state=UP_ACCELERATING", "confirm=UP_ACCELERATING");
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
    void rejectsWhenConfirmIsNeutralEvenIfPrimaryMaAndKlineAreStrong() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                flatMa7(), upMa25(),
                "110");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_CONFIRM_NOT_SUPPORT");
    }

    @Test
    void rejectsWhenConfirmOnlyBroadlySupportsButIsDecelerating() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                slowingButStillAboveMa7(), slowUpMa25(),
                "110");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_CONFIRM_NOT_SUPPORT");
    }

    @Test
    void rejectsEarlyMa25LagWhenMa25SlopeIsBelowZero() {
        // 60d ETH 回测：MA25 容忍度从 0.02 收到 0；MA25 斜率为负的早进信号一律拒。
        MarketContext ctx = marketContext(
                upMa7(), slightlyLaggingMa25BelowPrice(),
                upMa7(), upMa25(),
                "110");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_NO_STRONG_STATE");
    }

    @Test
    void rejectsEarlyMa25LagWhenMomentumIsOnlyPartiallySupportive() {
        MarketContext ctx = marketContext(
                upMa7(), slightlyLaggingMa25BelowPrice(),
                upMa7(), upMa25(),
                "110",
                "TREND_UP",
                "golden",
                "falling_5",
                "2",
                "1",
                "falling_5");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        // 新阈值下连 candidate 都没有；保留断言 reject 即可，具体拒因走 NO_STRONG_STATE 分支。
        assertThat(result.rejectReason()).contains("MASLOPE_NO_STRONG_STATE");
    }

    @Test
    void rejectsSqueezeWhenMaIsStrongButKlineDoesNotSupport() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "110",
                "SQUEEZE",
                "golden",
                "rising_5",
                "2",
                "1",
                "falling_5");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_KLINE_NOT_SUPPORT");
    }

    @Test
    void acceptsSqueezeWhenMaAndKlineLookStrong() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "110",
                "SQUEEZE");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.rejectReason()).isNull();
        assertThat(result.candidate()).isNotNull();
    }

    @Test
    void rejectsSqueezeWhenBollingerDoesNotExpand() {
        MarketContext ctx = marketContextWithAdxAndBollExpansion(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "110",
                "SQUEEZE",
                "26",
                false);

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_SQUEEZE_BREAKOUT_NOT_CONFIRMED");
    }

    @Test
    void acceptsRangeWhenMaAndKlineLookStrong() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "110",
                "RANGE");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.rejectReason()).isNull();
        assertThat(result.candidate()).isNotNull();
    }

    @Test
    void rejectsWhenMaSlopeHasNoMomentumSupport() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "110",
                "TREND_UP",
                "death",
                "falling_5",
                "1",
                "2",
                "falling_5");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_MACD_AGAINST");
    }

    @Test
    void acceptsEthM3WhenOneHourTrendContinuationIsStrong() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "110");

        EntryStrategyResult result = strategy.build(context("ETHUSDT", KlineInterval.M3, ctx));

        assertThat(result.rejectReason()).isNull();
        assertThat(result.candidate()).isNotNull();
        assertThat(result.candidate().side()).isEqualTo("LONG");
    }

    @Test
    void rejectsBtcM3UntilSeparateEdgeCalibration() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "110");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", KlineInterval.M3, ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_3M_BTC_NO_EDGE");
    }

    @Test
    void acceptsEthM3PullbackReclaimWhenOneHourAlignedAndQualityIsStrong() {
        MarketContext ctx = marketContext(
                pullbackReclaimMa7(), pullbackUpMa25(),
                upMa7(), upMa25(),
                "106.9",
                "TREND_UP",
                "golden",
                "rising_5",
                "2",
                "1",
                "rising_5",
                "30",
                true);

        EntryStrategyResult result = strategy.build(context("ETHUSDT", KlineInterval.M3, ctx));

        assertThat(result.rejectReason()).isNull();
        assertThat(result.candidate()).isNotNull();
        assertThat(result.candidate().reason()).contains("mode=PRIMARY_KLINE");
    }

    @Test
    void acceptsPullbackReclaimEntryModeAfterMa7Retest() {
        MarketContext ctx = marketContext(
                pullbackReclaimMa7(), pullbackUpMa25(),
                upMa7(), upMa25(),
                "128",
                "TREND_UP",
                "golden",
                "rising_5",
                "2",
                "1",
                "rising_5",
                "30",
                true);

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.rejectReason()).isNull();
        assertThat(result.candidate()).isNotNull();
        assertThat(result.candidate().entryMode()).isEqualTo("PULLBACK_RECLAIM");
        assertThat(result.candidate().wasLateContinuation()).isFalse();
    }

    @Test
    void rejectsM3ContinuationWhenVolumeAndMomentumAreWeak() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "110",
                "TREND_UP",
                "golden",
                "rising_5",
                "2",
                "1",
                "rising_5",
                "26",
                false,
                List.of(0.35, 0.40, 0.42, 0.38, 0.36));

        EntryStrategyResult result = strategy.build(context("ETHUSDT", KlineInterval.M3, ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_3M_TREND_CONTINUATION_WEAK");
    }

    @Test
    void rejectsWhenPriceTooFarFromMa25() {
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "200",
                "TREND_UP",
                "golden",
                "rising_5",
                "2",
                "1",
                "rising_5",
                "26",
                true,
                List.of(1.2, 1.3, 1.4, 1.3, 1.2),
                false,
                false);

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_LATE_CHASE_REJECT");
    }

    @Test
    void rejectsLateContinuationEvenWithStructureVolumeAndHealthyClose() {
        // 60d ETH 回测：LATE_CONTINUATION 6 笔 0 胜（净亏 278U），模式已关，超 PULLBACK 距离一律拒。
        MarketContext ctx = marketContext(
                upMa7(), upMa25(),
                upMa7(), upMa25(),
                "200");

        EntryStrategyResult result = strategy.build(context("BTCUSDT", ctx));

        assertThat(result.candidate()).isNull();
        assertThat(result.rejectReason()).contains("MASLOPE_LATE_CHASE_REJECT");
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
                acceleratingUpMa7(), upMa25(),
                acceleratingUpMa7(), upMa25(),
                "114");

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
        return marketContext(primaryMa7, primaryMa25, confirmMa7, confirmMa25, price, "TREND_UP");
    }

    private static MarketContext marketContext(List<BigDecimal> primaryMa7,
                                               List<BigDecimal> primaryMa25,
                                               List<BigDecimal> confirmMa7,
                                               List<BigDecimal> confirmMa25,
                                               String price,
                                               String regime) {
        return marketContext(primaryMa7, primaryMa25, confirmMa7, confirmMa25, price,
                regime, "golden", "rising_5", "2", "1", "rising_5");
    }

    private static MarketContext marketContext(List<BigDecimal> primaryMa7,
                                               List<BigDecimal> primaryMa25,
                                               List<BigDecimal> confirmMa7,
                                               List<BigDecimal> confirmMa25,
                                               String price,
                                               String regime,
                                               String macdCross,
                                               String macdHistTrend,
                                               String macdDif,
                                               String macdDea,
                                               String closeTrend) {
        return marketContext(primaryMa7, primaryMa25, confirmMa7, confirmMa25, price,
                regime, macdCross, macdHistTrend, macdDif, macdDea, closeTrend, "26", true);
    }

    private static MarketContext marketContextWithAdxAndBollExpansion(List<BigDecimal> primaryMa7,
                                                                      List<BigDecimal> primaryMa25,
                                                                      List<BigDecimal> confirmMa7,
                                                                      List<BigDecimal> confirmMa25,
                                                                      String price,
                                                                      String regime,
                                                                      String adx,
                                                                      boolean bollExpanding) {
        return marketContext(primaryMa7, primaryMa25, confirmMa7, confirmMa25, price,
                regime, "golden", "rising_5", "2", "1", "rising_5", adx, bollExpanding);
    }

    private static MarketContext marketContext(List<BigDecimal> primaryMa7,
                                               List<BigDecimal> primaryMa25,
                                               List<BigDecimal> confirmMa7,
                                               List<BigDecimal> confirmMa25,
                                               String price,
                                               String regime,
                                               String macdCross,
                                               String macdHistTrend,
                                               String macdDif,
                                               String macdDea,
                                               String closeTrend,
                                               String adx,
                                               boolean bollExpanding) {
        return marketContext(primaryMa7, primaryMa25, confirmMa7, confirmMa25, price,
                regime, macdCross, macdHistTrend, macdDif, macdDea, closeTrend,
                adx, bollExpanding, List.of(1.2, 1.3, 1.4, 1.3, 1.2),
                true, false);
    }

    private static MarketContext marketContext(List<BigDecimal> primaryMa7,
                                               List<BigDecimal> primaryMa25,
                                               List<BigDecimal> confirmMa7,
                                               List<BigDecimal> confirmMa25,
                                               String price,
                                               String regime,
                                               String macdCross,
                                               String macdHistTrend,
                                               String macdDif,
                                               String macdDea,
                                               String closeTrend,
                                               String adx,
                                               boolean bollExpanding,
                                               List<Double> volumeRatios) {
        return marketContext(primaryMa7, primaryMa25, confirmMa7, confirmMa25, price,
                regime, macdCross, macdHistTrend, macdDif, macdDea, closeTrend,
                adx, bollExpanding, volumeRatios, true, false);
    }

    private static MarketContext marketContext(List<BigDecimal> primaryMa7,
                                               List<BigDecimal> primaryMa25,
                                               List<BigDecimal> confirmMa7,
                                               List<BigDecimal> confirmMa25,
                                               String price,
                                               String regime,
                                               String macdCross,
                                               String macdHistTrend,
                                               String macdDif,
                                               String macdDea,
                                               String closeTrend,
                                               String adx,
                                               boolean bollExpanding,
                                               List<Double> volumeRatios,
                                               boolean breakout,
                                               boolean breakdown) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "regime": "%s",
                  "bollSqueeze": %s,
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
                      "close_series_closed": %s,
                      "adx": %s,
                      "plus_di": 30,
                      "minus_di": 12,
                      "atr14_closed": 10,
                      "atr_series_closed": [9,10],
                      "atr_mean_30_closed": 9,
                      "atr_spike_ratio": 1.1,
                      "macd_cross": "%s",
                      "macd_hist_trend": "%s",
                      "macd_dif": %s,
                      "macd_dea": %s,
                      "ema20": 100,
                      "ma_alignment": 1,
                      "close_trend": "%s",
                      "close_trend_recent_3_closed": "%s",
                      "close_position_closed": 0.80,
                      "range_atr_closed": 0.80,
                      "close_breakout_high_10_closed": %s,
                      "close_breakdown_low_10_closed": %s,
                      "boll_pb": 70,
                      "boll_bandwidth": 2,
                      "boll_expanding_5": %s,
                      "volume_ratio_recent_5_closed": %s
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
                regime,
                Boolean.toString("SQUEEZE".equals(regime)),
                primaryMa7.getLast().toPlainString(),
                primaryMa25.getLast().toPlainString(),
                array(primaryMa7),
                array(primaryMa25),
                array(primaryMa7),
                adx,
                macdCross,
                macdHistTrend,
                macdDif,
                macdDea,
                closeTrend,
                closeTrend,
                Boolean.toString(breakout),
                Boolean.toString(breakdown),
                bollExpanding,
                volumeRatios,
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
                      "close_series_closed": %s,
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
                      "close_trend_recent_3_closed": "rising_3",
                      "close_position_closed": 0.80,
                      "range_atr_closed": 0.80,
                      "close_breakout_high_10_closed": true,
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
                array(primaryMa7),
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

    private static List<BigDecimal> acceleratingUpMa7() {
        return bdList("100", "101", "102", "103", "104", "105.5", "107.5", "110", "113");
    }

    private static List<BigDecimal> upMa25() {
        return bdList("98", "98.5", "99", "99.5", "100", "100.5", "101", "101.5", "102");
    }

    private static List<BigDecimal> slowUpMa25() {
        return bdList("100", "100.5", "101", "101.5", "102", "102.5", "103", "103.5", "104");
    }

    private static List<BigDecimal> slowingButStillAboveMa7() {
        return bdList("105", "106", "107", "108", "109", "109.5", "109.8", "109.9", "109.95");
    }

    private static List<BigDecimal> slightlyLaggingMa25BelowPrice() {
        return bdList("99.00", "98.95", "98.90", "98.85", "98.80", "98.75", "98.70", "98.65", "98.60");
    }

    private static List<BigDecimal> pullbackReclaimMa7() {
        return bdList("104.0", "104.3", "104.4", "104.2", "104.0", "103.8", "103.7", "104.2", "106.8");
    }

    private static List<BigDecimal> pullbackUpMa25() {
        return bdList("100.0", "100.2", "100.4", "100.6", "100.8", "101.0", "101.2", "101.4", "101.6");
    }

    private static List<BigDecimal> downMa7() {
        return bdList("108", "107", "106", "105", "104", "103", "102", "101", "100");
    }

    private static List<BigDecimal> downMa25() {
        return bdList("109.5", "109", "108.5", "108", "107.5", "107", "106.5", "106", "105.5");
    }

    private static List<BigDecimal> flatMa7() {
        return bdList("100", "100", "100", "100", "100", "100", "100", "100", "100");
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
