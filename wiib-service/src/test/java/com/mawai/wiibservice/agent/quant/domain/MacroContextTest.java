package com.mawai.wiibservice.agent.quant.domain;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.MarketRegime;
import com.mawai.wiibservice.agent.research.forecast.VolatilityRiskTier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MacroContextTest {

    @Test
    void debateBlockExcludesDirectionButReportContainsIt() {
        MacroContext context = new MacroContext("BTCUSDT", Instant.now(), Map.of(
                ForecastHorizon.H6, new MacroContext.Leg(VolatilityRiskTier.NORMAL, 1.0, 120, 0.5,
                        MarketRegime.TRENDING_UP, 0.7, 1, 0.8),
                ForecastHorizon.H12, new MacroContext.Leg(VolatilityRiskTier.ELEVATED, 0.75, 180, 0.85,
                        MarketRegime.RANGING, 0.6, -1, 0.7),
                ForecastHorizon.H24, new MacroContext.Leg(VolatilityRiskTier.STRESSED, 0.5, 260, 0.96,
                        MarketRegime.SHOCK, 0.8, 0, 0.2)
        ), false, java.util.List.of());

        assertThat(context.toDebateBlock())
                .contains("vol:")
                .contains("regime:")
                .doesNotContain("direction")
                .doesNotContain("LONG")
                .doesNotContain("SHORT");
        assertThat(context.toReportBlock())
                .contains("direction")
                .contains("LONG")
                .contains("SHORT");
    }

    @Test
    void neutralContextDoesNotApplyRisk() {
        MacroRiskHint hint = MacroContext.neutral("BTCUSDT").toRiskHint();

        assertThat(hint.neutralHint()).isTrue();
        assertThat(hint.budgetMultiplier()).isEqualTo(1.0);
    }
}
