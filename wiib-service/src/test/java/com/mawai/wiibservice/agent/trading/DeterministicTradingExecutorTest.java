package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibservice.agent.trading.exit.model.ExitPath;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlanFactory;
import com.mawai.wiibservice.agent.trading.ops.TradingOperations;

import com.mawai.wiibservice.agent.trading.backtest.SignalReplayBacktestEngine;

import com.mawai.wiibservice.agent.trading.backtest.BacktestTradingTools;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicTradingExecutorTest {

    @Test
    void strongBreakoutCanOverrideFlatEntry() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(breakoutSnapshot(List.of()), "FLAT"),
                List.of(signal("0_10", "LONG", "0.65")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(tools.openCount).isEqualTo(1);
        assertThat(result.reasoning()).contains("overallDecision=FLAT强突破覆盖");
    }

    @Test
    void flatDecisionAllowsMeanReversionEntry() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(deepWeakeningMeanReversionSnapshot(List.of()), "FLAT"),
                List.of(signal("0_10", "LONG", "0.65")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(tools.openCount).isEqualTo(1);
        assertThat(result.reasoning()).contains("[Strategy-MR]");
        assertThat(result.reasoning()).contains("overallDecision=FLAT MR覆盖");
    }

    @Test
    void flatDecisionFiltersOrdinaryBreakoutBelowStrongScore() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(ordinaryBreakoutSnapshot(List.of()), "FLAT"),
                List.of(signal("0_10", "LONG", "0.65")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(tools.openCount).isZero();
        assertThat(result.reasoning()).contains("overallDecision=FLAT，仅MR/强突破可覆盖");
    }

    @Test
    void flatDecisionFiltersTrendContinuationCandidate() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(trendConfluenceSnapshot(List.of()), "FLAT"),
                List.of(signal("0_10", "LONG", "0.75")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(tools.openCount).isZero();
        assertThat(result.reasoning()).contains("overallDecision=FLAT，仅MR/强突破可覆盖");
    }

    @Test
    void upperHalfBandDoesNotCountAsBreakout() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(weakBreakoutSnapshot(List.of()), "FLAT"),
                List.of(signal("0_10", "LONG", "0.65")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(tools.openCount).isZero();
        assertThat(result.reasoning()).contains("未接近上轨突破");
    }

    @Test
    void nonSqueezeBreakoutWithoutDirectionalMomentumIsRejected() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(nonSqueezeNoMomentumBreakoutSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.75")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(tools.openCount).isZero();
        assertThat(result.reasoning()).contains("突破动能不足");
    }

    @Test
    void nonSqueezeBreakoutWithDirectionalMomentumCanOpen() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(nonSqueezeMomentumBreakoutSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.75")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(tools.openCount).isEqualTo(1);
        assertThat(result.reasoning()).contains("[Strategy-BREAKOUT]");
    }

    @Test
    void breakoutWithStrongOppositeMicroStructureIsRejectedEvenWhenFlatFiltersTrend() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(oppositeMicroBreakoutSnapshot(List.of()), "FLAT"),
                List.of(signal("0_10", "LONG", "0.75")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(tools.openCount).isZero();
        assertThat(result.reasoning()).contains("微结构明显反向");
    }


    @Test
    void lightMeanReversionStretchIsRejected() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(lightMeanReversionSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.60")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(tools.openCount).isZero();
        assertThat(result.reasoning()).contains("做多BB%B=24.00>20.00");
    }

    @Test
    void meanReversionRequiresTwoVotesWithoutDeepStretch() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(oneVoteMeanReversionSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.65")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(tools.openCount).isZero();
        assertThat(result.reasoning()).contains("反转确认不足 votes=1");
    }

    @Test
    void deepMeanReversionWithWeakeningCanOpenAgainstOldTrend() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(deepWeakeningMeanReversionSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.65")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(tools.openCount).isEqualTo(1);
        assertThat(result.reasoning()).contains("[Strategy-MR]");
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
                new TradingRuntimeToggles(true));

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
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(sig010.getConfidence()).isEqualByComparingTo("0.75");
        assertThat(sig1020.getConfidence()).isEqualByComparingTo("0.70");
        assertThat(sig2030.getConfidence()).isEqualByComparingTo("0.68");
    }

    @Test
    void activeHorizonOnlyBlocksLastMinuteOfEachWindow() {
        LocalDateTime forecastTime = LocalDateTime.of(2026, 1, 1, 9, 0);
        List<QuantSignalDecision> signals = List.of(signal("0_10", "LONG", "0.70"));

        assertThat(TradingDecisionSupport.findBestSignalWithPriority(
                signals, forecastTime, forecastTime.plusMinutes(8))).isNotNull();
        assertThat(TradingDecisionSupport.findBestSignalWithPriority(
                signals, forecastTime, forecastTime.plusMinutes(9))).isNull();
    }

    @Test
    void maxPositionPctCapsEntryQuantity() {
        StubTools tools = new StubTools();
        QuantSignalDecision sig = signal("0_10", "LONG", "0.75");
        sig.setMaxPositionPct(price("0.01"));

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(trendSnapshot(List.of()), "LONG"),
                List.of(sig),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(tools.lastOpenQty).isEqualByComparingTo("0.01000000");
    }

    @Test
    void playbookOpenSuccessStoresExitPlan() {
        StubTools tools = new StubTools();
        tools.nextOpenPositionId = 77L;
        TradingExecutionState state = new TradingExecutionState();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(breakoutSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.75")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, state, new TradingRuntimeToggles(true, true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        ExitPlan plan = state.getExitPlan(77L);
        assertThat(plan).isNotNull();
        assertThat(plan.path()).isEqualTo(ExitPath.BREAKOUT);
        assertThat(plan.side()).isEqualTo("LONG");
        assertThat(plan.entryPrice()).isEqualByComparingTo("100000");
        assertThat(plan.initialSL()).isEqualByComparingTo("98880.0");
        assertThat(plan.riskPerUnit()).isEqualByComparingTo("1120.0");
        assertThat(plan.atrAtEntry()).isEqualByComparingTo("400");
        assertThat(plan.entryBollPb()).isEqualTo(98.0);
        assertThat(plan.entryRsi()).isEqualTo(55.0);
        assertThat(plan.recovered()).isFalse();
    }

    @Test
    void playbookOpenFailureDoesNotDirtyExitPlanState() {
        StubTools tools = new StubTools();
        tools.nextOpenPositionId = 77L;
        tools.openFailureMessage = "开仓失败：mock";
        TradingExecutionState state = new TradingExecutionState();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(breakoutSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.75")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, state, new TradingRuntimeToggles(true, true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(state.getExitPlan(77L)).isNull();
        assertThat(state.getLastEntryMs("BTCUSDT")).isNull();
    }

    @Test
    void trendConfluenceGateAllowsEntryWithoutLegacySwitches() {
        StubTools tools = new StubTools();

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(trendConfluenceSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.75")),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(tools.openCount).isEqualTo(1);
        assertThat(result.reasoning()).contains("[Strategy-LEGACY_TREND]");
    }

    @Test
    void allNoTradeRiskStatusBlocksEntry() {
        StubTools tools = new StubTools();
        QuantSignalDecision sig = signal("0_10", "LONG", "0.75");
        sig.setRiskStatus("ALL_NO_TRADE");

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(), forecast(trendSnapshot(List.of()), "LONG"),
                List.of(sig),
                List.of(), price("100000"), price("100000"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(tools.openCount).isZero();
        assertThat(result.reasoning()).contains("ALL_NO_TRADE");
    }

    @Test
    void strongOppositeWithoutMarketRiskOnlyTightensStop() {
        StubTools tools = new StubTools();
        FuturesPositionDTO pos = position("LONG", "100000", "99200", "105200", "1");

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast(trendSnapshot(List.of("STALE_AGG_TRADE")), "LONG"),
                List.of(signal("0_10", "SHORT", "0.85")),
                List.of(), price("104200"), price("104200"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("TIGHTEN_SL");
        assertThat(tools.lastSlPrice).isGreaterThan(price("100000"));
        assertThat(tools.closeCount).isZero();
    }

    @Test
    void playbookEnabledCleanHoldContinuesToEntryEvaluation() {
        StubTools tools = new StubTools();
        tools.nextOpenPositionId = 2L;
        TradingExecutionState state = new TradingExecutionState();
        FuturesPositionDTO pos = position("LONG", "100000", "99000", "105200", "1");

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast(trendSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.60")),
                List.of(), price("100100"), price("100100"), price("100000"),
                tools, state, new TradingRuntimeToggles(true, true));

        assertThat(result.action()).isEqualTo("OPEN_LONG");
        assertThat(result.reasoning()).contains("TREND");
        assertThat(result.reasoning()).contains("entry=");
        assertThat(tools.openCount).isEqualTo(1);
        assertThat(tools.closeCount).isZero();
        assertThat(state.getExitPlan(1L)).isNotNull();
        assertThat(state.getExitPlan(2L)).isNotNull();
    }

    @Test
    void playbookBlockedHoldDoesNotContinueToEntryEvaluation() {
        StubTools tools = new StubTools();
        TradingExecutionState state = new TradingExecutionState();
        FuturesPositionDTO pos = position("LONG", "100000", "99000", "105200", "1");
        pos.setId(null);

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast(trendSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.60")),
                List.of(), price("100100"), price("100100"), price("100000"),
                tools, state, new TradingRuntimeToggles(true, true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(result.reasoning()).contains("缺positionId");
        assertThat(result.reasoning()).doesNotContain("entry=");
        assertThat(tools.openCount).isZero();
        assertThat(tools.closeCount).isZero();
    }

    @Test
    void playbookEnabledExitHitReturnsImmediatelyWithoutEntryMerge() {
        StubTools tools = new StubTools();
        TradingExecutionState state = new TradingExecutionState();
        FuturesPositionDTO pos = position("LONG", "100000", "99000", "105200", "1");
        pos.setMemo("BREAKOUT");
        state.putExitPlan(1L, ExitPlanFactory.fromEntry("BREAKOUT", "LONG",
                price("100000"), price("99000"), price("400"),
                98.0, 55.0, 1, 1, LocalDateTime.now().minusMinutes(6)));

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast(failedBreakoutSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.75")),
                List.of(), price("100100"), price("100100"), price("100000"),
                tools, state, new TradingRuntimeToggles(true, true));

        assertThat(result.action()).isEqualTo("PLAYBOOK_EXIT_FULL");
        assertThat(result.reasoning()).contains("BREAKOUT_BB_RETURN_NEUTRAL");
        assertThat(result.reasoning()).doesNotContain("entry=");
        assertThat(tools.closeCount).isEqualTo(1);
        assertThat(tools.openCount).isZero();
        assertThat(state.getExitPlan(1L)).isNull();
    }

    @Test
    void playbookFailedActionHoldDoesNotContinueToEntryEvaluation() {
        StubTools tools = new StubTools();
        tools.closeResult = "平仓失败|mock";
        TradingExecutionState state = new TradingExecutionState();
        FuturesPositionDTO pos = position("LONG", "100000", "99000", "105200", "1");
        pos.setMemo("BREAKOUT");
        state.putExitPlan(1L, ExitPlanFactory.fromEntry("BREAKOUT", "LONG",
                price("100000"), price("99000"), price("400"),
                98.0, 55.0, 1, 1, LocalDateTime.now().minusMinutes(6)));

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast(failedBreakoutSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.75")),
                List.of(), price("100100"), price("100100"), price("100000"),
                tools, state, new TradingRuntimeToggles(true, true));

        assertThat(result.action()).isEqualTo("HOLD");
        assertThat(result.reasoning()).contains("全平失败");
        assertThat(result.reasoning()).doesNotContain("entry=");
        assertThat(tools.closeCount).isEqualTo(1);
        assertThat(tools.openCount).isZero();
        assertThat(state.getExitPlan(1L)).isNotNull();
    }

    @Test
    void strongReversalExitsFullImmediately() {
        StubTools tools = new StubTools();
        TradingExecutionState state = new TradingExecutionState();
        FuturesPositionDTO pos = position("LONG", "100000", "99200", "105200", "1");
        QuantForecastCycle forecast = forecast(strongBearSnapshot(List.of("STALE_AGG_TRADE")), "LONG");
        List<QuantSignalDecision> signals = List.of(signal("0_10", "SHORT", "0.85"));

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast, signals,
                List.of(), price("104200"), price("104200"), price("100000"),
                tools, state, new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("EXIT_FULL_RISK");
        assertThat(tools.closeCount).isEqualTo(1);
        assertThat(tools.lastCloseQty).isEqualByComparingTo("1");
    }

    @Test
    void exitEvaluationRecoversMissingExitPlanBeforeEntryMerge() {
        StubTools tools = new StubTools();
        TradingExecutionState state = new TradingExecutionState();
        FuturesPositionDTO pos = position("LONG", "100000", "99200", "105200", "1");

        DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast(trendSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "LONG", "0.60")),
                List.of(), price("100100"), price("100100"), price("100000"),
                tools, state, new TradingRuntimeToggles(true));

        ExitPlan plan = state.getExitPlan(1L);
        assertThat(plan).isNotNull();
        assertThat(plan.recovered()).isTrue();
        assertThat(plan.path()).isEqualTo(ExitPath.TREND);
        assertThat(plan.riskPerUnit()).isEqualByComparingTo("800");
    }

    @Test
    void peakDrawdownWithRiskClosesHalfAndSyncsRiskOrders() {
        StubTools tools = new StubTools();
        TradingExecutionState state = new TradingExecutionState();
        FuturesPositionDTO pos = position("LONG", "100000", "99200", "105200", "1");

        DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast(trendSnapshot(List.of("STALE_AGG_TRADE")), "LONG"),
                List.of(signal("0_10", "LONG", "0.60")),
                List.of(), price("104000"), price("104000"), price("100000"),
                tools, state, new TradingRuntimeToggles(true));

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast(softBearSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "SHORT", "0.70")),
                List.of(), price("102800"), price("102800"), price("100000"),
                tools, state, new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("EXIT_PARTIAL_PROTECT");
        assertThat(tools.closeCount).isEqualTo(1);
        assertThat(tools.lastCloseQty).isEqualByComparingTo("0.50000000");
        assertThat(tools.lastSlQty).isEqualByComparingTo("0.50000000");
        assertThat(tools.lastTpQty).isEqualByComparingTo("0.50000000");
    }

    @Test
    void stalePositionWithRisingRiskUsesTimeExit() {
        StubTools tools = new StubTools();
        FuturesPositionDTO pos = position("LONG", "100000", "99200", "105200", "1");
        pos.setCreatedAt(LocalDateTime.now().minusMinutes(120));

        DeterministicTradingExecutor.ExecutionResult result = DeterministicTradingExecutor.execute(
                "BTCUSDT", user(), List.of(pos), forecast(timeExitBearSnapshot(List.of()), "LONG"),
                List.of(signal("0_10", "SHORT", "0.70")),
                List.of(), price("100200"), price("100200"), price("100000"),
                tools, new TradingExecutionState(),
                new TradingRuntimeToggles(true));

        assertThat(result.action()).isEqualTo("TIME_EXIT");
        assertThat(tools.closeCount).isEqualTo(1);
        assertThat(tools.lastCloseQty).isEqualByComparingTo("1");
    }

    @Test
    void backtestOpenPositionUsesMockTime() {
        LocalDateTime mockNow = LocalDateTime.of(2026, 1, 1, 9, 30);
        BacktestTradingTools tools = new BacktestTradingTools(price("100000"), "BTCUSDT");
        tools.setCurrentPrice(price("100000"));
        tools.setCurrentTime(mockNow);

        String result = tools.openPosition("LONG", price("1"), 10, "MARKET",
                null, price("99000"), price("105000"), "TEST");

        assertThat(result).startsWith("开仓成功");
        assertThat(tools.getOpenPositions("BTCUSDT").getFirst().getCreatedAt()).isEqualTo(mockNow);
    }

    @Test
    void backtestOpenPositionWithResultReturnsPositionId() {
        BacktestTradingTools tools = new BacktestTradingTools(price("100000"), "BTCUSDT");
        tools.setCurrentPrice(price("100000"));

        TradingOperations.OpenResult result = tools.openPositionWithResult("LONG", price("1"), 10, "MARKET",
                null, price("99000"), price("105000"), "TEST");

        assertThat(result.success()).isTrue();
        assertThat(result.positionId()).isEqualTo(1L);
        assertThat(result.message()).startsWith("开仓成功");
    }

    @Test
    void replayPriceBarRangeIncludesClose() {
        SignalReplayBacktestEngine engine = new SignalReplayBacktestEngine(
                "BTCUSDT", List.of(), Map.of(), price("100000"));
        QuantForecastCycle closeAboveHigh = forecast("""
                {"lastPrice":100,"barHigh":98,"barLow":95}
                """, "LONG");
        QuantForecastCycle closeBelowLow = forecast("""
                {"lastPrice":90,"barHigh":98,"barLow":95}
                """, "LONG");

        SignalReplayBacktestEngine.PriceBar highClamped = engine.extractPriceBar(closeAboveHigh);
        SignalReplayBacktestEngine.PriceBar lowClamped = engine.extractPriceBar(closeBelowLow);

        assertThat(highClamped.high()).isEqualByComparingTo("100");
        assertThat(highClamped.low()).isEqualByComparingTo("95");
        assertThat(lowClamped.high()).isEqualByComparingTo("98");
        assertThat(lowClamped.low()).isEqualByComparingTo("90");
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
                "golden", "rising_3", null, "98", "1.4", "rising", "0.00", flags);
    }

    private static String weakBreakoutSnapshot(List<String> flags) {
        return snapshot("SQUEEZE", true, "400", "55", 1, 1,
                "golden", "rising_3", null, "75", "1.4", "rising", "0.00", flags);
    }

    private static String failedBreakoutSnapshot(List<String> flags) {
        return snapshot("SQUEEZE", true, "400", "55", 1, 1,
                "golden", "rising_3", null, "55", "1.4", "rising", "0.00", flags);
    }

    private static String nonSqueezeNoMomentumBreakoutSnapshot(List<String> flags) {
        return snapshot("RANGE", false, "400", "78", 0, 1,
                "death", "flat", "99900", "92", "1.4", "flat", "0.00", flags);
    }

    private static String nonSqueezeMomentumBreakoutSnapshot(List<String> flags) {
        return snapshot("RANGE", false, "400", "55", 0, 1,
                "golden", "rising_3", "99900", "92", "1.4", "flat", "0.00", flags);
    }

    private static String ordinaryBreakoutSnapshot(List<String> flags) {
        return snapshot("RANGE", false, "400", "55", 0, 1,
                "golden", "rising_3", null, "89", "1.2", "rising", "0.00", flags);
    }

    private static String oppositeMicroBreakoutSnapshot(List<String> flags) {
        return snapshot("SQUEEZE", true, "400", "55", 1, 1,
                "golden", "rising_3", "99900", "98", "1.4", "rising", "-0.40", flags);
    }

    private static String lightMeanReversionSnapshot(List<String> flags) {
        return snapshot("RANGE", false, "400", "43", 0, 0,
                "death", "flat", null, "24", "1.0", "flat", "0.00", flags);
    }

    private static String oneVoteMeanReversionSnapshot(List<String> flags) {
        return snapshot("RANGE", false, "400", "42", 0, 0,
                "golden", "flat", null, "18", "1.0", "flat", "0.00", flags);
    }

    private static String deepWeakeningMeanReversionSnapshot(List<String> flags) {
        return snapshot("RANGE", "WEAKENING", false, "400", "38", -1, -1,
                null, "falling_3", null, "15", "1.0", "falling", "0.00", flags);
    }

    private static String trendSnapshot(List<String> flags) {
        return snapshot("TREND_UP", false, "400", "55", 1, 1,
                "golden", "rising_3", "99900", "60", "1.4", "rising", "0.30", flags);
    }

    private static String trendConfluenceSnapshot(List<String> flags) {
        return snapshot("TREND_UP", false, "400", "55", 1, 1,
                "golden", "rising_3", "99900", "60", "1.0", "rising", "0.00", flags);
    }

    private static String strongBearSnapshot(List<String> flags) {
        return snapshot("TREND_UP", false, "400", "55", 1, -1,
                null, "falling_3", "99900", "60", "1.4", "falling", "-0.30", flags);
    }

    private static String softBearSnapshot(List<String> flags) {
        return snapshot("TREND_UP", false, "400", "55", 1, -1,
                null, "rising_3", "99900", "60", "1.4", "rising", "0.30", flags);
    }

    private static String timeExitBearSnapshot(List<String> flags) {
        return snapshot("TREND_UP", false, "400", "55", 1, -1,
                null, "falling_3", "99900", "60", "1.4", "falling", "0.00", flags);
    }

    private static String snapshot(String regime, boolean squeeze, String atr, String rsi,
                                   int ma1h, int ma15m, String macdCross, String macdHist,
                                   String ema20, String bollPb, String volumeRatio,
                                   String closeTrend, String micro, List<String> flags) {
        return snapshot(regime, null, squeeze, atr, rsi, ma1h, ma15m, macdCross, macdHist,
                ema20, bollPb, volumeRatio, closeTrend, micro, flags);
    }

    private static String snapshot(String regime, String transition, boolean squeeze, String atr, String rsi,
                                   int ma1h, int ma15m, String macdCross, String macdHist,
                                   String ema20, String bollPb, String volumeRatio,
                                   String closeTrend, String micro, List<String> flags) {
        String flagsJson = flags.stream().map(f -> "\"" + f + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return """
                {
                  "regime": "%s",
                  "regimeTransition": %s,
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
                """.formatted(regime, transition == null ? "null" : "\"" + transition + "\"",
                atr, squeeze, micro, flagsJson, rsi,
                macdCross == null ? "null" : "\"" + macdCross + "\"", macdHist,
                ema20 == null ? "null" : ema20, closeTrend, bollPb, volumeRatio, ma15m, ma1h);
    }

    private static BigDecimal price(String value) {
        return new BigDecimal(value);
    }

    private static class StubTools implements TradingOperations {
        int openCount;
        int closeCount;
        Long nextOpenPositionId = 1L;
        String openFailureMessage;
        BigDecimal lastOpenQty;
        BigDecimal lastOpenSl;
        BigDecimal lastOpenTp;
        BigDecimal lastCloseQty;
        BigDecimal lastSlPrice;
        BigDecimal lastSlQty;
        BigDecimal lastTpQty;
        String closeResult = "平仓成功|test";

        @Override
        public String openPosition(String side, BigDecimal quantity, Integer leverage, String orderType,
                                   BigDecimal limitPrice, BigDecimal stopLossPrice,
                                   BigDecimal takeProfitPrice, String memo) {
            openCount++;
            lastOpenQty = quantity;
            lastOpenSl = stopLossPrice;
            lastOpenTp = takeProfitPrice;
            if (openFailureMessage != null) {
                return openFailureMessage;
            }
            return "开仓成功|posId=" + nextOpenPositionId;
        }

        @Override
        public String closePosition(Long positionId, BigDecimal quantity) {
            closeCount++;
            lastCloseQty = quantity;
            return closeResult;
        }

        @Override
        public String setStopLoss(Long positionId, BigDecimal stopLossPrice, BigDecimal quantity) {
            lastSlPrice = stopLossPrice;
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
