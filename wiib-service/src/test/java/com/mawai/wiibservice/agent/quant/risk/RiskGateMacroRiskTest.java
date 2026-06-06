package com.mawai.wiibservice.agent.quant.risk;

import com.mawai.wiibservice.agent.quant.domain.Direction;
import com.mawai.wiibservice.agent.quant.domain.HorizonForecast;
import com.mawai.wiibservice.agent.quant.domain.MacroRiskHint;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskGateMacroRiskTest {

    @AfterEach
    void resetToggle() {
        RiskGate.MACRO_RISK_ENABLED = false;
    }

    @Test
    void macroRiskIsIgnoredWhenToggleOff() {
        HorizonForecast input = forecast();

        var neutral = RiskGate.apply(List.of(input), MarketRegime.RANGE, BigDecimal.ONE,
                BigDecimal.valueOf(1000), List.of(), 50, 0, "BTCUSDT",
                MacroRiskHint.neutral());
        var macro = RiskGate.apply(List.of(input), MarketRegime.RANGE, BigDecimal.ONE,
                BigDecimal.valueOf(1000), List.of(), 50, 0, "BTCUSDT",
                new MacroRiskHint(0.5, false, true));

        assertThat(macro.forecasts().getFirst().maxPositionPct())
                .isEqualTo(neutral.forecasts().getFirst().maxPositionPct());
    }

    @Test
    void macroRiskShrinksPositionWhenToggleOn() {
        RiskGate.MACRO_RISK_ENABLED = true;
        HorizonForecast input = forecast();

        var result = RiskGate.apply(List.of(input), MarketRegime.RANGE, BigDecimal.ONE,
                BigDecimal.valueOf(1000), List.of(), 50, 0, "BTCUSDT",
                new MacroRiskHint(0.5, false, true));

        HorizonForecast clipped = result.forecasts().getFirst();
        assertThat(clipped.maxPositionPct()).isLessThan(input.maxPositionPct());
        assertThat(clipped.maxLeverage()).isLessThan(input.maxLeverage());
        assertThat(result.riskStatus()).contains("MACRO_BUDGET");
    }

    private static HorizonForecast forecast() {
        return new HorizonForecast("0_10", Direction.LONG, 1.0, 0.6, 0.1,
                BigDecimal.valueOf(99), BigDecimal.valueOf(101), BigDecimal.valueOf(98),
                BigDecimal.valueOf(102), BigDecimal.valueOf(103), 35, 0.2);
    }
}
