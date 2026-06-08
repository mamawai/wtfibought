package com.mawai.wiibservice.agent.quant.judge;

import com.mawai.wiibservice.agent.quant.domain.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HorizonDecisionPolicyTest {

    @Test
    void h6DirectionalPrioritizesH6() {
        var forecasts = List.of(
                new ConsensusForecast("H6", Direction.LONG, 0.62, 0.1),
                new ConsensusForecast("H12", Direction.NO_TRADE, 0.0, 0.2),
                new ConsensusForecast("H24", Direction.SHORT, 0.7, 0.1));
        assertThat(HorizonDecisionPolicy.overallDecision(forecasts)).isEqualTo("PRIORITIZE_H6_LONG");
    }

    /** 核心修复：H6 无方向时，即便 H24 置信度最高也不能单独 PRIORITIZE_H24。 */
    @Test
    void h24AloneNeverPrioritized() {
        var forecasts = List.of(
                new ConsensusForecast("H6", Direction.NO_TRADE, 0.0, 0.2),
                new ConsensusForecast("H12", Direction.NO_TRADE, 0.0, 0.2),
                new ConsensusForecast("H24", Direction.LONG, 0.95, 0.1));
        assertThat(HorizonDecisionPolicy.overallDecision(forecasts)).isEqualTo("FLAT");
    }

    @Test
    void h12AndH24SameStrongDirectionGoesConservative() {
        var forecasts = List.of(
                new ConsensusForecast("H6", Direction.NO_TRADE, 0.0, 0.2),
                new ConsensusForecast("H12", Direction.LONG, 0.6, 0.1),
                new ConsensusForecast("H24", Direction.LONG, 0.6, 0.1));
        assertThat(HorizonDecisionPolicy.overallDecision(forecasts)).isEqualTo("CONSERVATIVE_H12_LONG");
    }

    @Test
    void h12AndH24WeakConfidenceStaysFlat() {
        var forecasts = List.of(
                new ConsensusForecast("H6", Direction.NO_TRADE, 0.0, 0.2),
                new ConsensusForecast("H12", Direction.LONG, 0.5, 0.1),
                new ConsensusForecast("H24", Direction.LONG, 0.5, 0.1));
        assertThat(HorizonDecisionPolicy.overallDecision(forecasts)).isEqualTo("FLAT");
    }

    @Test
    void riskStatusAllNoTradeWhenEveryHorizonFlat() {
        var forecasts = List.of(
                new ConsensusForecast("H6", Direction.NO_TRADE, 0.0, 0.1),
                new ConsensusForecast("H12", Direction.NO_TRADE, 0.0, 0.1),
                new ConsensusForecast("H24", Direction.NO_TRADE, 0.0, 0.1));
        assertThat(HorizonDecisionPolicy.overallRiskStatus(forecasts)).isEqualTo("ALL_NO_TRADE");
    }
}
