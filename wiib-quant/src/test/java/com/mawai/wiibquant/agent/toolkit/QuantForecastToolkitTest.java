package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibquant.agent.quant.domain.MacroContext;
import com.mawai.wiibquant.agent.quant.service.MacroContextService;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.agent.research.forecast.MarketRegime;
import com.mawai.wiibquant.agent.research.forecast.VolatilityRiskTier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuantForecastToolkitTest {

    private final MacroContextService macroContextService = mock(MacroContextService.class);
    private final com.mawai.wiibquant.agent.analysis.ScorecardService scorecardService =
            mock(com.mawai.wiibquant.agent.analysis.ScorecardService.class);
    private final QuantForecastToolkit toolkit = new QuantForecastToolkit(macroContextService, scorecardService);

    private MacroContext contextWithDirection() {
        // 故意塞满方向数据，验证工具层过滤干净
        MacroContext.Leg leg = new MacroContext.Leg(
                VolatilityRiskTier.ELEVATED, 0.6, 120, 0.8,
                MarketRegime.TRENDING_UP, 0.7, 1, 0.9);
        return new MacroContext("BTCUSDT", Instant.now(),
                Map.of(ForecastHorizon.H6, leg, ForecastHorizon.H12, leg, ForecastHorizon.H24, leg),
                false, List.of());
    }

    @Test
    void volForecastOutputsThreeHorizonsWithoutDirection() {
        when(macroContextService.computeNow(eq("BTCUSDT"), anyLong())).thenReturn(contextWithDirection());

        String json = toolkit.volForecast("BTCUSDT");

        assertThat(json).contains("\"H6\"").contains("\"H12\"").contains("\"H24\"");
        assertThat(json).contains("ELEVATED").contains("120");
        // 铁律：方向预测无 edge，不给 agent 喂任何方向数字
        assertThat(json).doesNotContainIgnoringCase("direction");
    }

    @Test
    void marketRegimeOutputsRegimePerHorizonWithoutDirection() {
        when(macroContextService.computeNow(eq("BTCUSDT"), anyLong())).thenReturn(contextWithDirection());

        String json = toolkit.marketRegime("BTCUSDT");

        assertThat(json).contains("TRENDING_UP").contains("0.7");
        assertThat(json).doesNotContainIgnoringCase("direction");
    }

    @Test
    void staleContextReportsUnavailable() {
        when(macroContextService.computeNow(eq("BTCUSDT"), anyLong()))
                .thenReturn(MacroContext.neutral("BTCUSDT"));

        String json = toolkit.volForecast("BTCUSDT");

        assertThat(json).contains("\"available\":false");
    }
}
