package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VolatilityRiskContextTest {

    private static final long FIVE_MINUTES_MS = 5 * 60_000L;

    @Test
    void buildsLlmContextFromPointInTimeHistory() {
        VolatilityRiskContext c = VolatilityRiskContext.from(
                ForecastHorizon.H6, 0.0165, horizonReturnBars(ForecastHorizon.H6, returns()));

        assertThat(c.expectedMoveBps()).isEqualTo(165);
        assertThat(c.trailingPercentile()).isEqualTo(0.80);
        assertThat(c.riskTier()).isEqualTo(VolatilityRiskTier.ELEVATED);
        assertThat(c.riskBudgetHint()).isEqualTo(0.75);
        assertThat(c.riskFlags()).containsExactly("VOL_ELEVATED");
        assertThat(c.llmContext()).contains("expected_move_bps=165")
                .contains("risk_tier=ELEVATED")
                .contains("risk_budget_hint=0.75");
    }

    @Test
    void shortHistoryIsUnknownAndDoesNotForceRiskCut() {
        VolatilityRiskContext c = VolatilityRiskContext.from(
                ForecastHorizon.H24, 0.02, horizonReturnBars(ForecastHorizon.H24, 0.01, -0.01));

        assertThat(c.riskTier()).isEqualTo(VolatilityRiskTier.UNKNOWN);
        assertThat(c.trailingPercentile()).isEqualTo(0.5);
        assertThat(c.riskBudgetHint()).isEqualTo(1.0);
        assertThat(c.riskFlags()).contains("VOL_HISTORY_SHORT");
    }

    @Test
    void highPercentileIsStressed() {
        VolatilityRiskContext c = VolatilityRiskContext.from(
                ForecastHorizon.H12, 0.020, horizonReturnBars(ForecastHorizon.H12, returns()));

        assertThat(c.riskTier()).isEqualTo(VolatilityRiskTier.STRESSED);
        assertThat(c.riskBudgetHint()).isEqualTo(0.50);
        assertThat(c.riskFlags()).containsExactly("VOL_STRESSED");
    }

    private static double[] returns() {
        double[] out = new double[20];
        for (int i = 0; i < out.length; i++) {
            out[i] = 0.001 * (i + 1);
        }
        return out;
    }

    private static List<KlineBar> horizonReturnBars(ForecastHorizon horizon, double... horizonReturns) {
        java.util.ArrayList<KlineBar> out = new java.util.ArrayList<>();
        int horizonBars = Math.toIntExact(horizon.millis() / FIVE_MINUTES_MS);
        for (int i = 0; i < horizonBars; i++) {
            out.add(bar(i, 100.0));
        }
        for (int i = 0; i < horizonReturns.length; i++) {
            out.add(bar(horizonBars + i, 100.0 * Math.exp(horizonReturns[i])));
        }
        return out;
    }

    private static KlineBar bar(int i, double close) {
        BigDecimal c = BigDecimal.valueOf(close);
        long openTime = i * FIVE_MINUTES_MS;
        return new KlineBar(openTime, openTime + FIVE_MINUTES_MS - 1, c, c, c, c, BigDecimal.ONE);
    }
}
