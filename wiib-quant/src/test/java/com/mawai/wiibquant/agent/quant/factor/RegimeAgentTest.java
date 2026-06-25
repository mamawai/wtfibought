package com.mawai.wiibquant.agent.quant.factor;

import com.mawai.wiibquant.agent.quant.domain.AgentVote;
import com.mawai.wiibquant.agent.quant.domain.Direction;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.MacroContext;
import com.mawai.wiibquant.agent.quant.domain.MarketRegime;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.agent.research.forecast.VolatilityRiskTier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegimeAgentTest {

    private final RegimeAgent agent = new RegimeAgent();

    @Test
    void emitsSoftVetoWhenLiveRegimeConflictsWithResearchDirection() {
        FeatureSnapshot snapshot = snapshot(MarketRegime.TREND_DOWN, "BREAKING_DOWN", 10, 35);
        MacroContext research = research(ForecastHorizon.H6, 1, 0.70);

        AgentVote h6 = vote(agent.evaluate(snapshot, new FactorEvaluationContext(research)), "H6");

        assertThat(h6.direction()).isEqualTo(Direction.SHORT);
        assertThat(h6.score()).isLessThan(0);
        assertThat(h6.confidence()).isGreaterThan(0);
        assertThat(h6.reasonCodes()).contains("REGIME_VETO_AGAINST_RESEARCH");
        assertThat(h6.riskFlags()).contains("REGIME_RESEARCH_CONFLICT");
    }

    @Test
    void emitsSupportWhenLiveRegimeAlignsWithResearchDirection() {
        FeatureSnapshot snapshot = snapshot(MarketRegime.TREND_UP, "STRENGTHENING", 35, 10);
        MacroContext research = research(ForecastHorizon.H6, 1, 0.70);

        AgentVote h6 = vote(agent.evaluate(snapshot, new FactorEvaluationContext(research)), "H6");

        assertThat(h6.direction()).isEqualTo(Direction.LONG);
        assertThat(h6.score()).isGreaterThan(0);
        assertThat(h6.reasonCodes()).contains("REGIME_SUPPORTS_RESEARCH");
        assertThat(h6.riskFlags()).doesNotContain("REGIME_RESEARCH_CONFLICT");
    }

    @Test
    void staysRiskOnlyWhenResearchDirectionIsNeutral() {
        FeatureSnapshot snapshot = snapshot(MarketRegime.TREND_UP, "STRENGTHENING", 35, 10);
        MacroContext research = research(ForecastHorizon.H6, 0, 0.0);

        AgentVote h6 = vote(agent.evaluate(snapshot, new FactorEvaluationContext(research)), "H6");

        assertThat(h6.direction()).isEqualTo(Direction.NO_TRADE);
        assertThat(h6.score()).isZero();
    }

    @Test
    void defaultEvaluateKeepsRiskOnlyCompatibility() {
        FeatureSnapshot snapshot = snapshot(MarketRegime.TREND_UP, "STRENGTHENING", 35, 10);

        AgentVote h6 = vote(agent.evaluate(snapshot), "H6");

        assertThat(h6.direction()).isEqualTo(Direction.NO_TRADE);
        assertThat(h6.score()).isZero();
    }

    @Test
    void defaultedLiveRegimeDoesNotBreakConsistencyVote() {
        FeatureSnapshot snapshot = snapshot(null, "STRENGTHENING", 35, 10);
        MacroContext research = research(ForecastHorizon.H6, 1, 0.70);

        AgentVote h6 = vote(agent.evaluate(snapshot, new FactorEvaluationContext(research)), "H6");

        assertThat(h6.direction()).isEqualTo(Direction.LONG);
        assertThat(h6.reasonCodes()).contains("LIVE_REGIME_RANGE");
        assertThat(h6.riskFlags()).contains("RANGE_BOUND");
    }

    private static AgentVote vote(List<AgentVote> votes, String horizon) {
        return votes.stream()
                .filter(v -> horizon.equals(v.horizon()))
                .findFirst()
                .orElseThrow();
    }

    private static MacroContext research(ForecastHorizon horizon, int directionSign, double directionConfidence) {
        return new MacroContext("BTCUSDT", Instant.now(), Map.of(horizon,
                new MacroContext.Leg(VolatilityRiskTier.NORMAL, 1.0, 120, 0.5,
                        com.mawai.wiibquant.agent.research.forecast.MarketRegime.RANGING,
                        0.6, directionSign, directionConfidence)), false, List.of());
    }

    private static FeatureSnapshot snapshot(MarketRegime regime, String transition,
                                            double plusDi, double minusDi) {
        Map<String, Object> indicators15m = Map.of(
                "plus_di", BigDecimal.valueOf(plusDi),
                "minus_di", BigDecimal.valueOf(minusDi));
        return new FeatureSnapshot(
                "BTCUSDT", LocalDateTime.now(), BigDecimal.valueOf(100000),
                null, null, null,
                Map.of("15m", indicators15m), Map.of(),
                0, null, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                -1, "UNKNOWN",
                null, BigDecimal.valueOf(400), null, false,
                0, 0, 0, 0,
                regime, List.of(), List.of(),
                0.80, transition);
    }
}
