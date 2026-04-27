package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import com.mawai.wiibcommon.entity.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicTradingExecutorTest {

    @Test
    void fiveOfSevenAllowsBreakoutEntry() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(breakoutSnapshot(List.of()), "FLAT"),
                List.of(signal("0_10", "LONG", "0.55")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true, true, true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(tools.openCount).isEqualTo(1);
        assertThat(result.reasoning()).contains("overallDecision=FLAT仅记录");
    }

    @Test
    void existingPositionDoesNotBlockAnotherEntry() {
        StubTools tools = new StubTools();
        FuturesPositionDTO pos = position("LONG", "100000", "99000", "105200", "1");

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast(trendSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.60")),
                List.of(), price("100100"), price("100100"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true, true, true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(tools.openCount).isEqualTo(1);
        assertThat(tools.closeCount).isZero();
    }

    @Test
    void multiHorizonBoostDoesNotMutateOriginalSignals() {
        StubTools tools = new StubTools();
        QuantSignalDecision sig010 = signal("0_10", "LONG", "0.75");
        QuantSignalDecision sig1020 = signal("10_20", "LONG", "0.70");
        QuantSignalDecision sig2030 = signal("20_30", "LONG", "0.68");
        QuantForecastCycle forecast = forecast(trendSnapshot(List.of()), "LONG");
        forecast.setForecastTime(null);

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast,
                List.of(sig010, sig1020, sig2030),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true, true, true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(sig010.getConfidence()).isEqualByComparingTo("0.75");
        assertThat(sig1020.getConfidence()).isEqualByComparingTo("0.70");
        assertThat(sig2030.getConfidence()).isEqualByComparingTo("0.68");
    }

    @Test
    void seventyPercentProgressWithoutStrongReversalKeepsPosition() {
        StubTools tools = new StubTools();
        FuturesPositionDTO pos = position("LONG", "100000", "99200", "105200", "1");

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast(trendSnapshot(List.of("STALE_AGG_TRADE")), "LONG"),
                List.of(signal("0_10", "SHORT", "0.85")),
                List.of(), price("104200"), price("104200"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true, true, true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(result.reasoning()).contains("无强反转");
        assertThat(tools.closeCount).isZero();
    }

    @Test
    void seventyPercentProgressWithStrongReversalClosesHalfAfterTwoConfirmations() {
        StubTools tools = new StubTools();
        TradingExecutionState state = new TradingExecutionState();
        FuturesPositionDTO pos = position("LONG", "100000", "99200", "105200", "1");
        QuantForecastCycle forecast = forecast(strongBearSnapshot(List.of("STALE_AGG_TRADE")), "LONG");
        List<QuantSignalDecision> signals = List.of(signal("0_10", "SHORT", "0.85"));

        DeterministicTradingExecutor.ExecutionResult first = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast, signals,
                List.of(), price("104200"), price("104200"), price("100000"),
                tools, state, new TradingRuntimeToggles(true, true, true));
        DeterministicTradingExecutor.ExecutionResult second = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast, signals,
                List.of(), price("104200"), price("104200"), price("100000"),
                tools, state, new TradingRuntimeToggles(true, true, true));

        assertThat(first.action()).isEqualTo("HOLD");
        assertThat(second.action()).isEqualTo("PARTIAL_TP");
        assertThat(tools.closeCount).isEqualTo(1);
        assertThat(tools.lastCloseQty).isEqualByComparingTo("0.50000000");
        assertThat(tools.lastSlQty).isEqualByComparingTo("0.50000000");
        assertThat(tools.lastTpQty).isEqualByComparingTo("0.50000000");
    }

    private static User user() {
        User user = new User();
        user.setBalance(price("100000"));
        user.setFrozenBalance(BigDecimal.ZERO);
        return user;
    }

    private static QuantForecastCycle forecast(String snapshot, String decision) {
        QuantForecastCycle cycle = new QuantForecastCycle();
        cycle.setCycleId("test-cycle");
        cycle.setSymbol("BTCUSDT");
        cycle.setForecastTime(LocalDateTime.now());
        cycle.setOverallDecision(decision);
        cycle.setSnapshotJson(snapshot);
        return cycle;
    }

    private static QuantSignalDecision signal(String horizon, String direction, String confidence) {
        QuantSignalDecision signal = new QuantSignalDecision();
        signal.setHorizon(horizon);
        signal.setDirection(direction);
        signal.setConfidence(price(confidence));
        signal.setMaxLeverage(10);
        return signal;
    }

    private static FuturesPositionDTO position(String side, String entry, String sl, String tp, String quantity) {
        FuturesPositionDTO pos = new FuturesPositionDTO();
        pos.setId(1L);
        pos.setSymbol("BTCUSDT");
        pos.setSide(side);
        pos.setQuantity(price(quantity));
        pos.setEntryPrice(price(entry));
        pos.setStatus("OPEN");
        pos.setMemo("LEGACY_TREND");
        pos.setStopLosses(List.of(new FuturesStopLoss("sl-1", price(sl), price(quantity))));
        pos.setTakeProfits(List.of(new FuturesTakeProfit("tp-1", price(tp), price(quantity))));
        pos.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        return pos;
    }

    private static String breakoutSnapshot(List<String> flags) {
        return snapshot("SQUEEZE", true, "400", "55", 1, 1,
                "golden", "rising_3", null, "75", "1.4", "rising", "0.00", flags);
    }

    private static String trendSnapshot(List<String> flags) {
        return snapshot("TREND_UP", false, "400", "55", 1, 1,
                "golden", "rising_3", "99900", "60", "1.4", "rising", "0.30", flags);
    }

    private static String strongBearSnapshot(List<String> flags) {
        return snapshot("TREND_UP", false, "400", "55", 1, -1,
                null, "falling_3", "99900", "60", "1.4", "falling", "-0.30", flags);
    }

    private static String snapshot(String regime, boolean squeeze, String atr, String rsi,
                                   int ma1h, int ma15m, String macdCross, String macdHist,
                                   String ema20, String bollPb, String volumeRatio,
                                   String closeTrend, String micro, List<String> flags) {
        String flagsJson = flags.stream().map(f -> "\"" + f + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return """
                {
                  "regime": "%s",
                  "atr5m": %s,
                  "bollSqueeze": %s,
                  "bidAskImbalance": %s,
                  "takerBuySellPressure": 0,
                  "lsrExtreme": 0,
                  "qualityFlags": [%s],
                  "indicatorsByTimeframe": {
                    "5m": {
                      "rsi14": %s,
                      "macd_cross": %s,
                      "macd_hist_trend": "%s",
                      "macd_dif": 2,
                      "macd_dea": 1,
                      "ema20": %s,
                      "ma_alignment": 1,
                      "close_trend": "%s",
                      "boll_pb": %s,
                      "boll_bandwidth": 4,
                      "volume_ratio": %s
                    },
                    "15m": {"ma_alignment": %d, "rsi14": 55},
                    "1h": {"ma_alignment": %d}
                  }
                }
                """.formatted(regime, atr, squeeze, micro, flagsJson, rsi,
                macdCross == null ? "null" : "\"" + macdCross + "\"", macdHist,
                ema20 == null ? "null" : ema20, closeTrend, bollPb, volumeRatio, ma15m, ma1h);
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value);
    }

    private static class StubTools implements TradingOperations {
        int openCount;
        int closeCount;
        BigDecimal lastCloseQty;
        BigDecimal lastSlQty;
        BigDecimal lastTpQty;

        @Override
        public String openPosition(String side, BigDecimal quantity, Integer leverage, String orderType,
                                   BigDecimal limitPrice, BigDecimal stopLossPrice,
                                   BigDecimal takeProfitPrice, String memo) {
            openCount++;
            return "开仓成功|test";
        }

        @Override
        public String closePosition(Long positionId, BigDecimal quantity) {
            closeCount++;
            lastCloseQty = quantity;
            return "平仓成功|test";
        }

        @Override
        public String setStopLoss(Long positionId, BigDecimal stopLossPrice, BigDecimal quantity) {
            lastSlQty = quantity;
            return "修改止损成功";
        }

        @Override
        public String setTakeProfit(Long positionId, BigDecimal takeProfitPrice, BigDecimal quantity) {
            lastTpQty = quantity;
            return "修改止盈成功";
        }
    }
}
