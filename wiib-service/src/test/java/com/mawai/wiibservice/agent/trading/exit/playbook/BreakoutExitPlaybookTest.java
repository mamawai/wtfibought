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

class BreakoutExitPlaybookTest {

    private final BreakoutExitPlaybook playbook = new BreakoutExitPlaybook();

    @Test
    void exitsFailedBreakoutWhenBollReturnsNeutralBeforeBreakeven() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 1, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("100100", "55", "1", "100000", List.of()),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(6));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("BREAKOUT_BB_RETURN_NEUTRAL");
    }

    @Test
    void doesNotUseSingleCurrentLowVolumeAsExhaustion() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 1, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("100100", "88", "1", "100000", List.of(0.9, 0.9, 0.9)),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(10));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
    }

    @Test
    void ignoresVolumeExhaustionBeforeThreeClosedBarsAfterEntry() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 1, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("100100", "88", "1.2", "100000", List.of(0.7, 0.7, 0.7)),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(10));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.HOLD);
    }

    @Test
    void exitsWhenRecentClosedVolumeRatiosAllExhausted() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 1, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("100600", "88", "1.2", "100000", List.of(1.1, 0.7, 0.6, 0.5)),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(16));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("BREAKOUT_VOLUME_EXHAUSTION");
    }

    @Test
    void oneRiskBreakevenHasPriorityOverEarlyInvalidationOnSameTick() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 1, 0);
        ExitPlaybookDecision decision = playbook.evaluate(
                context("101000", "55", "1.2", "100000", List.of(0.7, 0.7, 0.7)),
                position("LONG", "100000", "99000", "1"),
                plan("LONG", entryAt),
                entryAt.plusMinutes(16));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.MOVE_STOP);
        assertThat(decision.stopLossPrice()).isEqualByComparingTo("100100.00");
        assertThat(decision.markBreakevenDone()).isTrue();
        assertThat(decision.reason()).isEqualTo("BREAKOUT_BREAKEVEN_1R");
    }

    @Test
    void twoRiskTriggersThirtyPercentPartialOnceBreakevenIsDone() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 1, 0);
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();

        ExitPlaybookDecision decision = playbook.evaluate(
                context("102000", "88", "1.2", "100000", List.of()),
                position("LONG", "100000", "100100", "1"),
                plan,
                entryAt.plusMinutes(20));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_PARTIAL);
        assertThat(decision.closeQuantity()).isEqualByComparingTo("0.30000000");
        assertThat(decision.partialMilestoneR()).isEqualTo(200);
    }

    @Test
    void twoRiskTrailUsesHighestProfitRAfterPartialMilestoneDone() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 1, 0);
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();
        plan.markPartialDoneAtR(200);

        ExitPlaybookDecision decision = playbook.evaluate(
                context("103000", "88", "1.2", "100000", List.of()),
                position("LONG", "100000", "100100", "1"),
                plan,
                entryAt.plusMinutes(25));

        assertThat(plan.highestProfitR()).isEqualTo(3.0);
        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.MOVE_STOP);
        assertThat(decision.stopLossPrice()).isEqualByComparingTo("102000.0");
        assertThat(decision.reason()).isEqualTo("BREAKOUT_CHANDELIER_TRAIL");
    }

    @Test
    void breakevenStructureReversalUsesMaAndEmaDualConfirm() {
        LocalDateTime entryAt = LocalDateTime.of(2026, 5, 7, 1, 0);
        ExitPlan plan = plan("LONG", entryAt);
        plan.markBreakevenDone();

        ExitPlaybookDecision decision = playbook.evaluate(
                context("100500", "88", "1.2", "101000", List.of(), -1),
                position("LONG", "100000", "100100", "1"),
                plan,
                entryAt.plusMinutes(20));

        assertThat(decision.action()).isEqualTo(ExitPlaybookDecision.Action.CLOSE_FULL);
        assertThat(decision.reason()).isEqualTo("BREAKOUT_STRUCTURE_REVERSAL_AFTER_BE");
    }

    private static TradingDecisionContext context(String price, String bollPb, String volumeRatio,
                                                  String ema20, List<Double> closedVolumeRatios) {
        return context(price, bollPb, volumeRatio, ema20, closedVolumeRatios, 1);
    }

    private static TradingDecisionContext context(String price, String bollPb, String volumeRatio,
                                                  String ema20, List<Double> closedVolumeRatios, int ma1h) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "atr5m": 400,
                  "indicatorsByTimeframe": {
                    "5m": {
                      "boll_pb": %s,
                      "volume_ratio": %s,
                      "volume_ratio_recent_5_closed": %s,
                      "ema20": %s
                    },
                    "1h": {"ma_alignment": %d}
                  }
                }
                """.formatted(bollPb, volumeRatio, closedVolumeRatios, ema20, ma1h));
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
        return ExitPlanFactory.fromEntry("BREAKOUT", side, bd("100000"), bd("99000"),
                bd("400"), 98.0, 55.0, 1, 1, entryAt);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
