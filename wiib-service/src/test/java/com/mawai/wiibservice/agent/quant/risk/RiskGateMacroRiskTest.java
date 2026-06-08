package com.mawai.wiibservice.agent.quant.risk;

import com.mawai.wiibservice.agent.quant.domain.Direction;
import com.mawai.wiibservice.agent.quant.domain.HorizonForecast;
import com.mawai.wiibservice.agent.quant.domain.MacroContext;
import com.mawai.wiibservice.agent.quant.domain.MacroRiskHint;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.VolatilityRiskTier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskGateMacroRiskTest {

    @Test
    void macroRiskHintAlwaysShrinksPosition() {
        HorizonForecast input = forecast();

        var neutral = RiskGate.apply(List.of(input), MarketRegime.RANGE, BigDecimal.ONE,
                BigDecimal.valueOf(1000), List.of(), 50, 0, "BTCUSDT",
                MacroRiskHint.neutral());
        var macro = RiskGate.apply(List.of(input), MarketRegime.RANGE, BigDecimal.ONE,
                BigDecimal.valueOf(1000), List.of(), 50, 0, "BTCUSDT",
                new MacroRiskHint(0.5, false, true));

        assertThat(macro.forecasts().getFirst().maxPositionPct())
                .isLessThan(neutral.forecasts().getFirst().maxPositionPct());
        assertThat(macro.riskStatus()).contains("MACRO_BUDGET");
    }

    @Test
    void macroRiskHintShrinksLeverageWhenStressed() {
        HorizonForecast input = forecast();

        var result = RiskGate.apply(List.of(input), MarketRegime.RANGE, BigDecimal.ONE,
                BigDecimal.valueOf(1000), List.of(), 50, 0, "BTCUSDT",
                new MacroRiskHint(0.5, false, true));

        HorizonForecast clipped = result.forecasts().getFirst();
        assertThat(clipped.maxPositionPct()).isLessThan(input.maxPositionPct());
        assertThat(clipped.maxLeverage()).isLessThan(input.maxLeverage());
        assertThat(result.riskStatus()).contains("MACRO_BUDGET");
    }

    /**
     * 回归：H24 + STRESSED + expectedMove≥750 时基准杠杆被压到 3，
     * 叠加高分歧(0.5>0.35)旧实现 {@code Math.clamp(maxLev/2, 5, maxLev)} 下界5>上界3 会抛 IllegalArgumentException。
     */
    @Test
    void highDisagreementWithLowGeneratedLeverageDoesNotThrow() {
        MacroContext.Leg h24Stressed = new MacroContext.Leg(
                VolatilityRiskTier.STRESSED, 0.5, 800, 0.5,
                com.mawai.wiibservice.agent.research.forecast.MarketRegime.RANGING,
                0.5, 1, 0.6);
        MacroContext research = new MacroContext("BTCUSDT", Instant.now(),
                Map.of(ForecastHorizon.H24, h24Stressed), false, List.of());
        HorizonForecast h24 = new HorizonForecast("H24", Direction.LONG, 0.8, 0.5, 0.5,
                null, null, null, null, null, 0, 0);

        var result = RiskGate.apply(List.of(h24), MarketRegime.RANGE, BigDecimal.ONE,
                BigDecimal.valueOf(1000), List.of(), 50, 0, "BTCUSDT", research);

        HorizonForecast clipped = result.forecasts().getFirst();
        assertThat(clipped.maxLeverage()).isGreaterThanOrEqualTo(1);
        assertThat(result.riskStatus()).contains("HIGH_DISAGREEMENT_PENALTY_H24");
    }

    private static HorizonForecast forecast() {
        return new HorizonForecast("H6", Direction.LONG, 1.0, 0.6, 0.1,
                null, null, null, null, null, 20, 0.2);
    }
}
