package com.mawai.wiibservice.agent.trading.exit.model;

import com.mawai.wiibservice.agent.trading.runtime.TradingExecutionState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExitPlanFactoryTest {

    @Test
    void mapsEntryPathsToExitPaths() {
        assertThat(ExitPlanFactory.mapPath("BREAKOUT")).isEqualTo(ExitPath.BREAKOUT);
        assertThat(ExitPlanFactory.mapPath("MR")).isEqualTo(ExitPath.MR);
        assertThat(ExitPlanFactory.mapPath("MEAN_REVERSION")).isEqualTo(ExitPath.MR);
        assertThat(ExitPlanFactory.mapPath("LEGACY_TREND")).isEqualTo(ExitPath.TREND);
        assertThat(ExitPlanFactory.mapPath("TREND")).isEqualTo(ExitPath.TREND);
        assertThat(ExitPlanFactory.mapPath("UNKNOWN")).isEqualTo(ExitPath.TREND);
        assertThat(ExitPlanFactory.mapPath(null)).isEqualTo(ExitPath.TREND);
    }

    @Test
    void createsEntryPlanWithRiskAndPathTimeLimit() {
        ExitPlan breakout = ExitPlanFactory.fromEntry(
                "BREAKOUT", "LONG", bd("100"), bd("95"), bd("2.5"),
                72.0, 61.0, 1, 1, LocalDateTime.of(2026, 5, 7, 10, 0));
        ExitPlan trend = ExitPlanFactory.fromEntry(
                "LEGACY_TREND", "SHORT", bd("100"), bd("106"), bd("2.5"),
                45.0, 48.0, -1, -1, LocalDateTime.of(2026, 5, 7, 10, 0));

        assertThat(breakout.path()).isEqualTo(ExitPath.BREAKOUT);
        assertThat(breakout.riskPerUnit()).isEqualByComparingTo("5");
        assertThat(breakout.timeLimit()).isEqualTo(Duration.ofMinutes(30));
        assertThat(breakout.recovered()).isFalse();
        assertThat(trend.path()).isEqualTo(ExitPath.TREND);
        assertThat(trend.riskPerUnit()).isEqualByComparingTo("6");
        assertThat(trend.timeLimit()).isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void rejectsZeroRiskPlan() {
        assertThatThrownBy(() -> ExitPlanFactory.fromEntry(
                "MR", "LONG", bd("100"), bd("100"), bd("2"),
                null, null, null, null, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riskPerUnit");
    }

    @Test
    void usesIntegerMilestonesAndPositiveHighestProfitOnly() {
        ExitPlan plan = ExitPlanFactory.fromEntry(
                "BREAKOUT", "LONG", bd("100"), bd("95"), bd("2"),
                null, null, null, null, LocalDateTime.now());

        plan.markPartialDoneAtR(200);
        plan.markPartialDoneAtR(200);
        plan.recordHighestProfitR(0.8);
        plan.recordHighestProfitR(-1.0);
        plan.recordHighestProfitR(0.6);
        plan.recordHighestProfitR(1.2);

        assertThat(plan.partialDoneAtR()).containsExactly(200);
        assertThat(plan.isPartialDoneAtR(200)).isTrue();
        assertThat(plan.isPartialDoneAtR(300)).isFalse();
        assertThat(plan.highestProfitR()).isEqualTo(1.2);
    }

    @Test
    void cleansExitPlanWithOtherPositionMemory() {
        TradingExecutionState state = new TradingExecutionState();
        ExitPlan livePlan = ExitPlanFactory.fromEntry(
                "BREAKOUT", "LONG", bd("100"), bd("95"), bd("2"),
                null, null, null, null, LocalDateTime.now());
        ExitPlan stalePlan = ExitPlanFactory.fromEntry(
                "MR", "LONG", bd("100"), bd("96"), bd("2"),
                null, null, null, null, LocalDateTime.now());

        state.putExitPlan(1L, livePlan);
        state.putExitPlan(2L, stalePlan);
        state.recordPositiveProfit(2L, bd("10"));
        state.incrementReversalStreak(2L);
        state.cleanupPositionMemory(List.of(1L));

        assertThat(state.getExitPlan(1L)).isSameAs(livePlan);
        assertThat(state.getExitPlan(2L)).isNull();
        assertThat(state.getPositionPeak(2L)).isNull();
        assertThat(state.removeExitPlan(1L)).isSameAs(livePlan);
        assertThat(state.getExitPlan(1L)).isNull();
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
