package com.mawai.wiibservice.agent.trading.exit.playbook;

import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlanFactory;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;
import com.mawai.wiibservice.agent.trading.runtime.TradingDecisionContext;
import com.mawai.wiibservice.agent.trading.runtime.TradingExecutionState;
import com.mawai.wiibservice.agent.trading.runtime.TradingRuntimeToggles;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeanReversionExitPlaybookTest {

    private final MeanReversionExitPlaybook playbook = new MeanReversionExitPlaybook();

    @Test
    void exitsWhenBollingerPercentMovesFivePointsMoreExtremeThanEntry() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 3, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("99900", "9.9", "38", "sideways", 0, 0),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", 15.0, entryAt),
                entryAt.plusMinutes(8));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("MR_BOLL_MORE_EXTREME");
    }

    @Test
    void exitsWhenThreeClosedBarsContinueAgainstPosition() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 3, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("99800", "16", "38", "falling_3", 0, 0),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", 15.0, entryAt),
                entryAt.plusMinutes(12));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("MR_THREE_CLOSED_BARS_AGAINST");
    }

    @Test
    void exitsWhenHigherTimeframesFlipIntoNewTrendAgainstPosition() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 3, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("99900", "16", "38", "sideways", -1, -1),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", 15.0, 0, 0, entryAt),
                entryAt.plusMinutes(12));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("MR_HIGHER_TF_TREND_AGAINST");
    }

    @Test
    void closesFullWhenBollingerOrRsiReturnsToNeutralZone() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 3, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("100800", "48", "39", "sideways", 0, 0),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", 15.0, entryAt),
                entryAt.plusMinutes(18));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("MR_MEAN_REACHED");
    }

    @Test
    void halfMidlinePathClosesHalfAndMovesStopToEntry() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 3, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("100300", "30", "42", "sideways", 0, 0),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", 10.0, entryAt),
                entryAt.plusMinutes(18));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_PARTIAL);
        assertThat(decision.closeQuantity()).isEqualByComparingTo("0.50000000");
        assertThat(decision.stopLossPrice()).isEqualByComparingTo("100000");
        assertThat(decision.markBreakevenDone()).isTrue();
        assertThat(decision.markMrMidlineHalfDone()).isTrue();
        assertThat(decision.reason()).isEqualTo("MR_MIDLINE_HALF_PROTECT");
    }

    @Test
    void shortHalfMidlinePathClosesHalfAndMovesStopToEntry() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 3, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("99700", "70", "58", "sideways", 0, 0),
                position("SHORT", "100000", "101000", "1"),
                plan("SHORT", 90.0, entryAt),
                entryAt.plusMinutes(18));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_PARTIAL);
        assertThat(decision.closeQuantity()).isEqualByComparingTo("0.50000000");
        assertThat(decision.stopLossPrice()).isEqualByComparingTo("100000");
        assertThat(decision.markMrMidlineHalfDone()).isTrue();
    }

    @Test
    void timeExitClosesWhenNoMeaningfulReversionAfterThirtyMinutes() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 3, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("100100", "20", "42", "sideways", 0, 0),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", 10.0, entryAt),
                entryAt.plusMinutes(31));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("MR_TIME_EXIT_NO_REVERSION");
    }

    @Test
    void recoveredPlanSkipsEntrySnapshotDependentInvalidation() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 3, 0);
        ExitPlan recovered = ExitPlanFactory.recovered(
                "MR", "LONG", bd("100000"), bd("99000"), bd("1000"),
                bd("400"), false, entryAt);

        ExitPlaybookDecision decision = playbook.evaluate(
                context("99900", "5", "38", "sideways", 0, 0),
                position("LONG", "100000", "99000", "1"),
                recovered,
                entryAt.plusMinutes(10));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
    }

    @Test
    void meanReachedHasPriorityOverHalfPathPartial() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 3, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("100700", "50", "42", "sideways", 0, 0),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", 10.0, entryAt),
                entryAt.plusMinutes(18));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("MR_MEAN_REACHED");
    }

    private static TradingDecisionContext context(String price, String bollPb, String rsi,
                                                  String closeTrendClosed3, int ma1h, int ma15m) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "atr5m": 400,
                  "indicatorsByTimeframe": {
                    "5m": {
                      "boll_pb": %s,
                      "rsi14": %s,
                      "close_trend_recent_3_closed": "%s"
                    },
                    "15m": {"ma_alignment": %d},
                    "1h": {"ma_alignment": %d}
                  }
                }
                """.formatted(bollPb, rsi, closeTrendClosed3, ma15m, ma1h));
        MarketContext market = MarketContext.parse(forecast, bd(price));
        return new TradingDecisionContext("BTCUSDT", null, List.of(), forecast, List.of(), List.of(),
                bd(price), bd(price), bd("100000"), null, new TradingExecutionState(),
                new TradingRuntimeToggles(true), SymbolProfile.of("BTCUSDT"), market, LocalDateTime.now());
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

    private static ExitPlan plan(String side, Double entryBollPb, LocalDateTime entryAt) {
        return plan(side, entryBollPb, 0, 0, entryAt);
    }

    private static ExitPlan plan(String side, Double entryBollPb, Integer entryMa1h, Integer entryMa15m,
                                 LocalDateTime entryAt) {
        String sl = "LONG".equals(side) ? "99000" : "101000";
        return ExitPlanFactory.fromEntry("MR", side, bd("100000"), bd(sl),
                bd("400"), entryBollPb, 38.0, entryMa1h, entryMa15m, entryAt);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
