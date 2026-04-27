package com.mawai.wiibservice.agent.quant;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.fastjson2.JSON;
import com.mawai.wiibservice.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibservice.agent.quant.domain.ForecastResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuantForecastFacade {

    private final AiAgentRuntimeManager aiAgentRuntimeManager;
    private final QuantForecastPersistService persistService;

    public QuantForecastRunResult run(String symbol, String threadId, Map<String, Object> extraState) throws Exception {
        Optional<OverAllState> result = aiAgentRuntimeManager.invokeQuantWithFallback(symbol, threadId, extraState);
        if (result.isEmpty()) {
            return new QuantForecastRunResult(false, false, null, null, null, null, null);
        }

        OverAllState state = result.get();
        boolean dataAvailable = (boolean) state.value("data_available").orElse(true);
        if (!dataAvailable) {
            return new QuantForecastRunResult(true, false, null, null, null, null, null);
        }

        CryptoAnalysisReport report = null;
        Object reportObj = state.value("report").orElse(null);
        if (reportObj instanceof CryptoAnalysisReport r) {
            report = r;
        }

        Object fr = state.value("forecast_result").orElse(null);
        ForecastResult forecastResult = null;
        if (fr instanceof String frJson) {
            forecastResult = JSON.parseObject(frJson, ForecastResult.class);
        } else if (fr instanceof ForecastResult frObj) {
            forecastResult = frObj;
        }

        String debateSummary = (String) state.value("debate_summary").orElse(null);
        if (debateSummary == null) {
            debateSummary = (String) state.value("debate_shadow_summary").orElse(null);
        }
        String rawSnapshotJson = (String) state.value("raw_snapshot_json").orElse(null);
        String rawReportJson = (String) state.value("raw_report_json").orElse(null);

        if (forecastResult != null) {
            persistService.persist(forecastResult, debateSummary, rawSnapshotJson, rawReportJson);
        } else {
            log.warn("[Quant] forecast_result为空或类型异常 type={}", fr != null ? fr.getClass().getName() : "null");
        }

        return new QuantForecastRunResult(true, true, report, forecastResult,
                debateSummary, rawSnapshotJson, rawReportJson);
    }
}
