package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VolatilityRiskSummaryTest {

    @Test
    void summarizesExpectedMoveAndTierShares() {
        VolatilityRiskSummary s = VolatilityRiskSummary.from(List.of(
                context(100, VolatilityRiskTier.NORMAL),
                context(200, VolatilityRiskTier.ELEVATED),
                context(300, VolatilityRiskTier.STRESSED),
                context(400, VolatilityRiskTier.STRESSED)));

        assertThat(s.n()).isEqualTo(4);
        assertThat(s.medianExpectedMoveBps()).isEqualTo(200);
        assertThat(s.p90ExpectedMoveBps()).isEqualTo(400);
        assertThat(s.elevatedOrStressedShare()).isEqualTo(0.75);
        assertThat(s.stressedShare()).isEqualTo(0.50);
        assertThat(s.tierCounts()).containsEntry(VolatilityRiskTier.STRESSED, 2);
        assertThat(s.llmContext()).contains("median_expected_move_bps=200")
                .contains("p90_expected_move_bps=400");
    }

    @Test
    void emptySummaryIsStable() {
        VolatilityRiskSummary s = VolatilityRiskSummary.from(List.of());

        assertThat(s.n()).isZero();
        assertThat(s.llmContext()).isEqualTo("vol_context_summary{n=0}");
    }

    private static VolatilityRiskContext context(int moveBps, VolatilityRiskTier tier) {
        return new VolatilityRiskContext(ForecastHorizon.H6, moveBps / 10_000.0, moveBps,
                0.5, tier, 1.0, List.of(), "ctx");
    }
}
