package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.fastjson2.JSON;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.quant.domain.ForecastResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuantForecastFacadeTest {

    private final AiAgentRuntimeManager runtimeManager = mock(AiAgentRuntimeManager.class);
    private final QuantForecastPersistService persistService = mock(QuantForecastPersistService.class);
    private final QuantForecastFacade facade = new QuantForecastFacade(runtimeManager, persistService);

    @Test
    void returnsGraphFailedWhenGraphReturnsEmpty() {
        when(runtimeManager.invokeQuantWithFallback(anyString(), anyString(), eq(Map.of())))
                .thenReturn(Optional.empty());

        QuantForecastRunResult result = facade.run("BTCUSDT", "thread-1", Map.of());

        assertThat(result).isEqualTo(QuantForecastRunResult.graphFailed());
        verifyNoInteractions(persistService);
    }

    @Test
    void treatsMissingDataAvailableAsUnavailable() {
        ForecastResult forecast = forecastResult();
        when(runtimeManager.invokeQuantWithFallback(anyString(), anyString(), eq(Map.of())))
                .thenReturn(Optional.of(state(Map.of("forecast_result", JSON.toJSONString(forecast)))));

        QuantForecastRunResult result = facade.run("BTCUSDT", "thread-1", Map.of());

        assertThat(result).isEqualTo(QuantForecastRunResult.dataUnavailable());
        verifyNoInteractions(persistService);
    }

    @Test
    void parsesForecastResultJsonAndPersists() {
        ForecastResult forecast = forecastResult();
        CryptoAnalysisReport report = new CryptoAnalysisReport();
        Map<String, Object> values = new HashMap<>();
        values.put("data_available", true);
        values.put("report", report);
        values.put("forecast_result", JSON.toJSONString(forecast));
        values.put("debate_summary", "{\"judgeReasoning\":\"ok\"}");
        values.put("raw_snapshot_json", "{\"snapshot\":true}");
        values.put("raw_report_json", "{\"report\":true}");
        when(runtimeManager.invokeQuantWithFallback(anyString(), anyString(), eq(Map.of())))
                .thenReturn(Optional.of(state(values)));

        QuantForecastRunResult result = facade.run("BTCUSDT", "thread-1", Map.of());

        assertThat(result.graphReturned()).isTrue();
        assertThat(result.dataAvailable()).isTrue();
        assertThat(result.report()).isSameAs(report);
        assertThat(result.forecastResult()).isEqualTo(forecast);
        verify(persistService).persist(forecast,
                "{\"judgeReasoning\":\"ok\"}",
                "{\"snapshot\":true}",
                "{\"report\":true}");
    }

    @Test
    void acceptsForecastResultObjectAndPersists() {
        ForecastResult forecast = forecastResult();
        when(runtimeManager.invokeQuantWithFallback(anyString(), anyString(), eq(Map.of())))
                .thenReturn(Optional.of(state(Map.of(
                        "data_available", true,
                        "forecast_result", forecast
                ))));

        QuantForecastRunResult result = facade.run("BTCUSDT", "thread-1", Map.of());

        assertThat(result.forecastResult()).isSameAs(forecast);
        verify(persistService).persist(forecast, null, null, null);
    }

    private static OverAllState state(Map<String, Object> values) {
        return new OverAllState(values);
    }

    private static ForecastResult forecastResult() {
        return new ForecastResult("BTCUSDT", "cycle-1",
                LocalDateTime.of(2026, 6, 8, 12, 0),
                List.of(), "FLAT", "NORMAL", List.of(),
                null, null, null, null, null, null, null);
    }
}
