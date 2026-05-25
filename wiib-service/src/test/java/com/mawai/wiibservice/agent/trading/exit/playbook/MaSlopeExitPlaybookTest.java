package com.mawai.wiibservice.agent.trading.exit.playbook;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlanFactory;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;
import com.mawai.wiibservice.agent.trading.runtime.TradingDecisionContext;
import com.mawai.wiibservice.agent.trading.runtime.TradingExecutionState;
import com.mawai.wiibservice.agent.trading.runtime.TradingRuntimeToggles;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaSlopeExitPlaybookTest {

    private final MaSlopeExitPlaybook playbook = new MaSlopeExitPlaybook();

    @Test
    void missingDataBlocksEvaluation() {
        ExitPlaybookDecision decision = playbook.evaluate(null, null, null, null);

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(decision.entryEvaluationBlocked()).isTrue();
    }

    @Test
    void oneRiskMovesStopToBreakevenBeforeSignalExit() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);
        ExitPlan plan = plan("LONG", entryAt);

        ExitPlaybookDecision decision = playbook.evaluate(
                priceContext("101000", "400", false),
                position("LONG", "100000", "99000", "1"),
                plan,
                entryAt.plusMinutes(10));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.MOVE_STOP);
        assertThat(decision.stopLossPrice()).isEqualByComparingTo("100000");
        assertThat(decision.markBreakevenDone()).isTrue();
        assertThat(decision.reason()).isEqualTo("MA_SLOPE_BREAKEVEN_1R");
    }

    @Test
    void longClosesFullWhenMaSlopeTurnsStrongDown() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                signalContext("100500", "10", downMa7(), downMa25(), false, new TradingExecutionState()),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(12));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).contains("MA_SLOPE_SIGNAL_REVERSE", "DOWN_ACCELERATING");
    }

    @Test
    void longHoldsPrimaryReverseWhenHigherTrendStillSupports() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                signalContextWithConfirm("100500", "10",
                        downMa7(), downMa25(), upMa7(), upMa25(),
                        false, new TradingExecutionState()),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(12));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(decision.reason()).isEqualTo("MA_SLOPE_HOLD");
    }

    @Test
    void extinguishAfterBreakevenRequiresTwoConsecutiveSignals() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);
        TradingExecutionState state = new TradingExecutionState();
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();
        FuturesPositionDTO position = position("LONG", "100000", "100000", "1");
        TradingDecisionContext context = signalContext(
                "100500", "10", extinguishingLongMa7(), risingMa25(), false, state);
        TradingDecisionContext nextBar = signalContext(
                "100450", "10", extinguishingLongMa7NextBar(), risingMa25NextBar(), false, state);

        ExitPlaybookDecision first = playbook.evaluate(context, position, plan, entryAt.plusMinutes(20));
        ExitPlaybookDecision sameBarAgain = playbook.evaluate(context, position, plan, entryAt.plusMinutes(21));
        ExitPlaybookDecision secondBar = playbook.evaluate(nextBar, position, plan, entryAt.plusMinutes(25));

        assertThat(first.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(first.reason()).contains("MA_SLOPE_EXTINGUISH_WAIT", "streak=1");
        assertThat(sameBarAgain.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(sameBarAgain.reason()).contains("streak=1");
        assertThat(state.getReversalStreak(1L)).isEqualTo(2);
        assertThat(secondBar.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(secondBar.reason()).contains("MA_SLOPE_EXTINGUISH", "streak=2");
    }

    @Test
    void longTreatsDownDeceleratingAsExtinguishAfterBreakeven() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);
        TradingExecutionState state = new TradingExecutionState();
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();
        FuturesPositionDTO position = position("LONG", "100000", "100000", "1");

        ExitPlaybookDecision first = playbook.evaluate(
                signalContext("100200", "10", downDecelMa7(), flatMa25(), false, state),
                position, plan, entryAt.plusMinutes(20));
        ExitPlaybookDecision second = playbook.evaluate(
                signalContext("100100", "10", downDecelMa7NextBar(), flatMa25(), false, state),
                position, plan, entryAt.plusMinutes(25));

        assertThat(first.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(first.reason()).contains("DOWN_DECELERATING", "streak=1");
        assertThat(second.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(second.reason()).contains("MA_SLOPE_EXTINGUISH", "DOWN_DECELERATING");
    }

    @Test
    void extinguishIsSuppressedWhenHigherTrendStillSupports() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);
        TradingExecutionState state = new TradingExecutionState();
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();
        FuturesPositionDTO position = position("LONG", "100000", "100000", "1");

        ExitPlaybookDecision decision = playbook.evaluate(
                signalContextWithConfirm("100500", "10",
                        extinguishingLongMa7(), risingMa25(),
                        extinguishingLongMa7(), risingMa25(),
                        false, state),
                position, plan, entryAt.plusMinutes(20));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(decision.reason()).isEqualTo("MA_SLOPE_HOLD");
        assertThat(state.getReversalStreak(1L)).isZero();
    }

    @Test
    void extinguishBeforeBreakevenDoesNotClose() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);
        TradingExecutionState state = new TradingExecutionState();

        ExitPlaybookDecision decision = playbook.evaluate(
                signalContext("100500", "10", extinguishingLongMa7(), risingMa25(), false, state),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(20));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(state.getReversalStreak(1L)).isZero();
    }

    @Test
    void fastFailClosesLongBeforeHardStopWhenDeepLossAndScoreEnough() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);

        ExitPlaybookDecision decision = playbook.evaluate(
                signalContext("99500", "10", downMa7(), downMa25(),
                        false, new TradingExecutionState()),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(12));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).contains("MA_SLOPE_FAST_FAIL", "threshold=2", "r=-0.50");
    }

    @Test
    void fastFailClosesShortBeforeHardStopWhenDeepLossAndScoreEnough() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);

        ExitPlaybookDecision decision = playbook.evaluate(
                signalContext("100500", "10", upMa7(), upMa25(),
                        false, new TradingExecutionState()),
                position("SHORT", "100000", "101000", "1"),
                plan("SHORT", entryAt),
                entryAt.plusMinutes(12));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).contains("MA_SLOPE_FAST_FAIL", "threshold=2", "r=-0.50");
    }

    @Test
    void fastFailRaisesThresholdWhenHigherTimeframeStillSupports() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);

        ExitPlaybookDecision decision = playbook.evaluate(
                signalContextWithConfirm("99500", "10",
                        downMa7(), downMa25(), upMa7(), upMa25(),
                        false, new TradingExecutionState()),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(12));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(decision.reason()).isEqualTo("MA_SLOPE_HOLD");
    }

    @Test
    void shallowFastFailRequiresTwoClosedBars() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);
        TradingExecutionState state = new TradingExecutionState();
        ExitPlan plan = plan("LONG", entryAt);
        FuturesPositionDTO position = position("LONG", "100000", "99000", "1");
        TradingDecisionContext firstBar = failureContext("99700", "10",
                downMa7(), downMa25(), List.of(), List.of(), false, state);
        TradingDecisionContext secondBar = failureContext("99700", "10",
                downMa7NextBar(), downMa25NextBar(), List.of(), List.of(), false, state);

        ExitPlaybookDecision first = playbook.evaluate(firstBar, position, plan, entryAt.plusMinutes(12));
        ExitPlaybookDecision sameBarAgain = playbook.evaluate(firstBar, position, plan, entryAt.plusMinutes(13));
        ExitPlaybookDecision second = playbook.evaluate(secondBar, position, plan, entryAt.plusMinutes(16));

        assertThat(first.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(first.reason()).contains("MA_SLOPE_FAST_FAIL_WAIT", "threshold=4", "streak=1");
        assertThat(sameBarAgain.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(sameBarAgain.reason()).contains("streak=1");
        assertThat(second.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(second.reason()).contains("MA_SLOPE_FAST_FAIL", "streak=2");
    }

    @Test
    void earlyFailureClosesBeforeBreakevenAfterSixtyMinutesStillNegative() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);

        ExitPlaybookDecision decision = playbook.evaluate(
                priceContext("99900", "400", false),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(60));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("MA_SLOPE_NO_PROGRESS_IN_60M");
    }

    @Test
    void earlyFailureDoesNotCloseWhenMaSlopeSignalStillStrong() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);

        ExitPlaybookDecision decision = playbook.evaluate(
                signalContext("100200", "10", upMa7(), upMa25(), false, new TradingExecutionState()),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(60));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(decision.reason()).isEqualTo("MA_SLOPE_HOLD");
    }

    @Test
    void earlyFailureDoesNotCloseWhenHigherTrendStillSupports() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);

        ExitPlaybookDecision decision = playbook.evaluate(
                signalContextWithConfirm("99900", "400",
                        List.of(), List.of(), upMa7(), upMa25(),
                        false, new TradingExecutionState()),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(60));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(decision.reason()).isEqualTo("MA_SLOPE_HOLD");
    }

    @Test
    void alreadyProtectedStopMarksBreakevenAndSkipsEarlyFailure() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);
        ExitPlan plan = plan("LONG", entryAt);

        ExitPlaybookDecision decision = playbook.evaluate(
                priceContext("100200", "400", false),
                position("LONG", "100000", "100000", "1"),
                plan,
                entryAt.plusMinutes(30));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(plan.breakevenDone()).isTrue();
    }

    @Test
    void threeRiskClosesThirtyPercentAndSyncsAtrTrail() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();

        ExitPlaybookDecision decision = playbook.evaluate(
                priceContext("103000", "400", false),
                position("LONG", "100000", "100000", "1"),
                plan,
                entryAt.plusMinutes(40));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_PARTIAL);
        assertThat(decision.closeQuantity()).isEqualByComparingTo("0.30000000");
        assertThat(decision.stopLossPrice()).isEqualByComparingTo("102200.0");
        assertThat(decision.partialMilestoneR()).isEqualTo(300);
        assertThat(decision.reason()).isEqualTo("MA_SLOPE_PARTIAL_3R");
    }

    @Test
    void twoRiskMovesStopToAtrTrail() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();

        ExitPlaybookDecision decision = playbook.evaluate(
                priceContext("102000", "400", false),
                position("LONG", "100000", "100000", "1"),
                plan,
                entryAt.plusMinutes(35));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.MOVE_STOP);
        assertThat(decision.stopLossPrice()).isEqualByComparingTo("101200.0");
        assertThat(decision.reason()).isEqualTo("MA_SLOPE_ATR_TRAIL_2R");
    }

    @Test
    void timeLimitClosesWhenNoHalfRiskProgressInNinetyMinutes() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);

        ExitPlaybookDecision decision = playbook.evaluate(
                priceContext("100300", "400", false),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(91));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("MA_SLOPE_NO_0_5R_IN_90M");
    }

    @Test
    void timeLimitDoesNotCloseWhenHigherTrendStillSupports() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);

        ExitPlaybookDecision decision = playbook.evaluate(
                signalContextWithConfirm("100300", "400",
                        List.of(), List.of(), upMa7(), upMa25(),
                        false, new TradingExecutionState()),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(91));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(decision.reason()).isEqualTo("MA_SLOPE_HOLD");
    }

    @Test
    void indicatorShieldSuppressesSignalExitButKeepsPriceProtection() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 24, 1, 0);

        ExitPlaybookDecision signalOnly = playbook.evaluate(
                signalContext("100500", "10", downMa7(), downMa25(), true, new TradingExecutionState()),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(12));
        ExitPlaybookDecision breakeven = playbook.evaluate(
                signalContext("101000", "10", downMa7(), downMa25(), true, new TradingExecutionState()),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(12));

        assertThat(signalOnly.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
        assertThat(signalOnly.reason()).isEqualTo("MA_SLOPE_HOLD");
        assertThat(breakeven.action()).isEqualTo(ExitPlaybookDecision.Action.MOVE_STOP);
        assertThat(breakeven.reason()).isEqualTo("MA_SLOPE_BREAKEVEN_1R");
    }

    private static TradingDecisionContext priceContext(String price, String atr, boolean shielded) {
        return context(price, atr, List.of(), List.of(), List.of(), List.of(), shielded, new TradingExecutionState());
    }

    private static TradingDecisionContext signalContext(String price, String atr,
                                                        List<BigDecimal> ma7,
                                                        List<BigDecimal> ma25,
                                                        boolean shielded,
                                                        TradingExecutionState state) {
        return context(price, atr, ma7, ma25, List.of(), List.of(), shielded, state);
    }

    private static TradingDecisionContext signalContextWithConfirm(String price, String atr,
                                                                   List<BigDecimal> ma7,
                                                                   List<BigDecimal> ma25,
                                                                   List<BigDecimal> confirmMa7,
                                                                   List<BigDecimal> confirmMa25,
                                                                   boolean shielded,
                                                                   TradingExecutionState state) {
        return context(price, atr, ma7, ma25, confirmMa7, confirmMa25, shielded, state);
    }

    private static TradingDecisionContext failureContext(String price, String atr,
                                                         List<BigDecimal> ma7,
                                                         List<BigDecimal> ma25,
                                                         List<BigDecimal> confirmMa7,
                                                         List<BigDecimal> confirmMa25,
                                                         boolean shielded,
                                                         TradingExecutionState state) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "atr": %s,
                  "indicatorsByTimeframe": {
                    "5m": {
                      "atr14_closed": %s,
                      "ma7_series_closed": %s,
                      "ma25_series_closed": %s,
                      "macd_cross": "death",
                      "macd_dif": -1,
                      "macd_dea": 1,
                      "plus_di": 10,
                      "minus_di": 30,
                      "close_trend_recent_3_closed": "falling_3"
                    },
                    "15m": {
                      "atr14_closed": %s,
                      "ma7_series_closed": %s,
                      "ma25_series_closed": %s
                    },
                    "1h": {"ma_alignment": 1}
                  }
                }
                """.formatted(atr, atr, array(ma7), array(ma25), atr, array(confirmMa7), array(confirmMa25)));
        MarketContext market = MarketContext.parse(forecast, bd(price), KlineInterval.M5);
        return new TradingDecisionContext("BTCUSDT", null, List.of(), forecast, List.of(), List.of(),
                bd(price), bd(price), bd("100000"), null, state,
                new TradingRuntimeToggles(true, true), SymbolProfile.of("BTCUSDT"), market,
                LocalDateTime.of(2026, 5, 24, 1, 0), shielded);
    }

    private static TradingDecisionContext context(String price, String atr,
                                                  List<BigDecimal> ma7,
                                                  List<BigDecimal> ma25,
                                                  List<BigDecimal> confirmMa7,
                                                  List<BigDecimal> confirmMa25,
                                                  boolean shielded,
                                                  TradingExecutionState state) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "atr": %s,
                  "indicatorsByTimeframe": {
                    "5m": {
                      "atr14_closed": %s,
                      "ma7_series_closed": %s,
                      "ma25_series_closed": %s
                    },
                    "15m": {
                      "atr14_closed": %s,
                      "ma7_series_closed": %s,
                      "ma25_series_closed": %s
                    }
                  }
                }
                """.formatted(atr, atr, array(ma7), array(ma25), atr, array(confirmMa7), array(confirmMa25)));
        MarketContext market = MarketContext.parse(forecast, bd(price), KlineInterval.M5);
        return new TradingDecisionContext("BTCUSDT", null, List.of(), forecast, List.of(), List.of(),
                bd(price), bd(price), bd("100000"), null, state,
                new TradingRuntimeToggles(true, true), SymbolProfile.of("BTCUSDT"), market,
                LocalDateTime.of(2026, 5, 24, 1, 0), shielded);
    }

    private static FuturesPositionDTO position(String side, String entry, String sl, String quantity) {
        FuturesPositionDTO pos = new FuturesPositionDTO();
        pos.setId(1L);
        pos.setSide(side);
        pos.setEntryPrice(bd(entry));
        pos.setQuantity(bd(quantity));
        pos.setStatus("OPEN");
        pos.setStopLosses(List.of(new FuturesStopLoss("sl-1", bd(sl), bd(quantity))));
        return pos;
    }

    private static ExitPlan plan(String side, LocalDateTime entryAt) {
        String sl = "LONG".equals(side) ? "99000" : "101000";
        return ExitPlanFactory.fromEntry("MA_SLOPE", side, bd("100000"), bd(sl),
                bd("400"), 60.0, 55.0, 1, 1, entryAt);
    }

    private static List<BigDecimal> downMa7() {
        return bdList("108", "107", "106", "105", "104", "103", "102", "101", "100");
    }

    private static List<BigDecimal> downMa7NextBar() {
        return bdList("107", "106", "105", "104", "103", "102", "101", "100", "99");
    }

    private static List<BigDecimal> downMa25() {
        return bdList("109.5", "109", "108.5", "108", "107.5", "107", "106.5", "106", "105.5");
    }

    private static List<BigDecimal> downMa25NextBar() {
        return bdList("109", "108.5", "108", "107.5", "107", "106.5", "106", "105.5", "105");
    }

    private static List<BigDecimal> extinguishingLongMa7() {
        return bdList("109", "110", "111", "112", "113", "112.8", "112.6", "112.4", "112.2");
    }

    private static List<BigDecimal> extinguishingLongMa7NextBar() {
        return bdList("110", "111", "112", "113", "112.8", "112.6", "112.4", "112.2", "112.0");
    }

    private static List<BigDecimal> risingMa25() {
        return bdList("99.5", "100", "100.5", "101", "101.5", "102", "102.5", "103", "103.5");
    }

    private static List<BigDecimal> risingMa25NextBar() {
        return bdList("100", "100.5", "101", "101.5", "102", "102.5", "103", "103.5", "104");
    }

    private static List<BigDecimal> downDecelMa7() {
        return bdList("100.2", "100.0", "99.8", "99.6", "99.4", "99.2", "99.0", "98.8", "98.6");
    }

    private static List<BigDecimal> downDecelMa7NextBar() {
        return bdList("100.0", "99.8", "99.6", "99.4", "99.2", "99.0", "98.8", "98.6", "98.4");
    }

    private static List<BigDecimal> flatMa25() {
        return bdList("102", "102", "102", "102", "102", "102", "102", "102", "102");
    }

    private static List<BigDecimal> upMa7() {
        return bdList("100", "101", "102", "103", "104", "105", "106", "107", "108");
    }

    private static List<BigDecimal> upMa25() {
        return bdList("98", "98.5", "99", "99.5", "100", "100.5", "101", "101.5", "102");
    }

    private static List<BigDecimal> bdList(String... values) {
        return java.util.Arrays.stream(values).map(MaSlopeExitPlaybookTest::bd).toList();
    }

    private static String array(List<BigDecimal> values) {
        return values.stream().map(BigDecimal::toPlainString).toList().toString();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
