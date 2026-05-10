package com.mawai.wiibservice.agent.trading.exit.playbook;

import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlanFactory;
import com.mawai.wiibservice.agent.trading.MarketContext;
import com.mawai.wiibservice.agent.trading.SymbolProfile;
import com.mawai.wiibservice.agent.trading.TradingDecisionContext;
import com.mawai.wiibservice.agent.trading.TradingExecutionState;
import com.mawai.wiibservice.agent.trading.TradingRuntimeToggles;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrendExitPlaybookTest {

    private final TrendExitPlaybook playbook = new TrendExitPlaybook();

    @Test
    void holdsBeforeBreakevenWhenOnlyOneHourMaReverses() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("100100", "99000", -1, "golden", "2", "1"),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(20));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
    }

    @Test
    void exitsBeforeBreakevenWhenOneHourMaAndEmaReverse() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("99900", "100000", -1, "golden", "2", "1"),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(20));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("TREND_1H_CONFIRM_REVERSE_BEFORE_BE");
    }

    @Test
    void exitsBeforeBreakevenWhenOneHourMaAndMacdReverse() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("100100", "99000", -1, "death", "1", "2"),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(20));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("TREND_1H_CONFIRM_REVERSE_BEFORE_BE");
    }

    @Test
    void exitsBeforeBreakevenOnlyWhenEmaAndMacdBothReverse() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("99900", "100000", 1, "death", "1", "2"),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(20));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("TREND_EMA_MACD_REVERSE_BEFORE_BE");
    }

    @Test
    void holdsWhenOnlyEmaReversesWithoutMacdConfirmation() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("99900", "100000", 1, "golden", "2", "1"),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(20));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
    }

    @Test
    void oneRiskBreakevenHasPriorityOverStructureFailureOnSameTick() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("101000", "102000", -1, "death", "1", "2"),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(20));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.MOVE_STOP);
        assertThat(decision.stopLossPrice()).isEqualByComparingTo("100000");
        assertThat(decision.markBreakevenDone()).isTrue();
        assertThat(decision.reason()).isEqualTo("TREND_BREAKEVEN_1R");
    }

    @Test
    void shortOneRiskMovesStopToEntry() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("99000", "98000", -1, "death", "1", "2"),
                position("SHORT", "100000", "101000", "1"),
                plan("SHORT", entryAt),
                entryAt.plusMinutes(20));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.MOVE_STOP);
        assertThat(decision.stopLossPrice()).isEqualByComparingTo("100000");
        assertThat(decision.markBreakevenDone()).isTrue();
    }

    @Test
    void trailStartsAtTwoRiskAndNotBefore() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();

        ExitPlaybookDecision beforeTwoR = playbook.evaluate(
                context("101900", "99000", 1, "golden", "2", "1"),
                position("LONG", "100000", "100000", "1"),
                plan,
                entryAt.plusMinutes(30));

        assertThat(beforeTwoR.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);

        ExitPlaybookDecision atTwoR = playbook.evaluate(
                context("102000", "99000", 1, "golden", "2", "1"),
                position("LONG", "100000", "100000", "1"),
                plan,
                entryAt.plusMinutes(35));

        assertThat(atTwoR.action()).isEqualTo(ExitPlaybookDecision.Action.MOVE_STOP);
        assertThat(atTwoR.stopLossPrice()).isEqualByComparingTo("101200.0");
        assertThat(atTwoR.reason()).isEqualTo("TREND_ATR_TRAIL_2R");
    }

    @Test
    void threeRiskTriggersThirtyPercentPartialOnceTrailAlreadyProtected() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();

        ExitPlaybookDecision decision = playbook.evaluate(
                context("103000", "99000", 1, "golden", "2", "1"),
                position("LONG", "100000", "102200", "1"),
                plan,
                entryAt.plusMinutes(40));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_PARTIAL);
        assertThat(decision.closeQuantity()).isEqualByComparingTo("0.30000000");
        assertThat(decision.partialMilestoneR()).isEqualTo(300);

        plan.markPartialDoneAtR(300);
        ExitPlaybookDecision afterDone = playbook.evaluate(
                context("103000", "99000", 1, "golden", "2", "1"),
                position("LONG", "100000", "102200", "1"),
                plan,
                entryAt.plusMinutes(45));

        assertThat(afterDone.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
    }

    @Test
    void threeRiskFromBreakevenStopSyncsTrailInSameCycle() {
        // SL 还在保本位（entry）就直接涨到 +3R 时，必须同 cycle 平 30% 并把剩余 70% 的 SL 抬到 ATR trail，
        // 否则 trail 每 tick 都 improve 会让 partial 永远进不去（F1 修复前的真 bug 路径）。
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();

        ExitPlaybookDecision decision = playbook.evaluate(
                context("103000", "99000", 1, "golden", "2", "1"),
                position("LONG", "100000", "100000", "1"),
                plan,
                entryAt.plusMinutes(40));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_PARTIAL);
        assertThat(decision.closeQuantity()).isEqualByComparingTo("0.30000000");
        assertThat(decision.partialMilestoneR()).isEqualTo(300);
        assertThat(decision.stopLossPrice()).isEqualByComparingTo("102200");
        assertThat(decision.reason()).isEqualTo("TREND_PARTIAL_3R");
    }

    @Test
    void holdsAfterBreakevenWhenOnlyOneHourMaReverses() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();

        ExitPlaybookDecision decision = playbook.evaluate(
                context("100500", "99000", -1, "golden", "2", "1"),
                position("LONG", "100000", "100000", "1"),
                plan,
                entryAt.plusMinutes(30));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
    }

    @Test
    void afterBreakevenOneHourMaAndEmaReverseClosesFull() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();

        ExitPlaybookDecision decision = playbook.evaluate(
                context("100500", "101000", -1, "golden", "2", "1"),
                position("LONG", "100000", "100000", "1"),
                plan,
                entryAt.plusMinutes(30));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("TREND_1H_CONFIRM_REVERSE_AFTER_BE");
    }

    @Test
    void afterBreakevenOneHourMaAndMacdReverseClosesFull() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();

        ExitPlaybookDecision decision = playbook.evaluate(
                context("100500", "99000", -1, "death", "1", "2"),
                position("LONG", "100000", "100000", "1"),
                plan,
                entryAt.plusMinutes(30));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("TREND_1H_CONFIRM_REVERSE_AFTER_BE");
    }

    @Test
    void exitsAfterNinetyMinutesWithoutHalfRiskProgress() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 2, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("100400", "99000", 1, "golden", "2", "1"),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(91));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("TREND_NO_0_5R_IN_90M");
    }

    private static TradingDecisionContext context(String price, String ema20, int ma1h,
                                                  String macdCross, String macdDif, String macdDea) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "atr5m": 400,
                  "indicatorsByTimeframe": {
                    "5m": {
                      "ema20": %s,
                      "macd_cross": "%s",
                      "macd_dif": %s,
                      "macd_dea": %s
                    },
                    "1h": {"ma_alignment": %d}
                  }
                }
                """.formatted(ema20, macdCross, macdDif, macdDea, ma1h));
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

    private static ExitPlan plan(String side, LocalDateTime entryAt) {
        String sl = "LONG".equals(side) ? "99000" : "101000";
        return ExitPlanFactory.fromEntry("LEGACY_TREND", side, bd("100000"), bd(sl),
                bd("400"), 60.0, 55.0, "LONG".equals(side) ? 1 : -1, "LONG".equals(side) ? 1 : -1, entryAt);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
