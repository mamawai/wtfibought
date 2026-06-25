package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.fastjson2.JSON;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.quant.domain.ForecastResult;
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

    public QuantForecastRunResult run(String symbol, String threadId, Map<String, Object> extraState) {
        String targetSymbol = requireSymbol(symbol);
        try {
            Optional<OverAllState> result = aiAgentRuntimeManager.invokeQuantWithFallback(targetSymbol, threadId, extraState);
            if (result.isEmpty()) {
                log.warn("[Quant] graph返回空状态 symbol={} threadId={}", targetSymbol, threadId);
                return QuantForecastRunResult.graphFailed();
            }
            return toRunResult(targetSymbol, result.get());
        } catch (QuantForecastRunException e) {
            throw e;
        } catch (Exception e) {
            throw new QuantForecastRunException("量化预测执行失败 symbol=" + targetSymbol, e);
        }
    }

    private static String requireSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol不能为空");
        }
        return symbol;
    }

    private QuantForecastRunResult toRunResult(String symbol, OverAllState state) {
        if (!dataAvailable(state)) {
            return QuantForecastRunResult.dataUnavailable();
        }

        CryptoAnalysisReport report = report(state.value("report").orElse(null));

        Object fr = state.value("forecast_result").orElse(null);
        ForecastResult forecastResult = forecastResult(fr);

        String debateSummary = stateString(state, "debate_summary");
        String rawSnapshotJson = stateString(state, "raw_snapshot_json");
        String rawReportJson = stateString(state, "raw_report_json");

        if (forecastResult != null) {
            persistService.persist(forecastResult, debateSummary, rawSnapshotJson, rawReportJson);
        } else {
            log.warn("[Quant] forecast_result为空或类型异常 symbol={} type={}", symbol, typeName(fr));
        }

        return QuantForecastRunResult.completed(report, forecastResult,
                debateSummary, rawSnapshotJson, rawReportJson);
    }

    private boolean dataAvailable(OverAllState state) {
        Object value = state.value("data_available").orElse(null);
        switch (value) {
            case null -> {
                return false;
            }
            case Boolean b -> {
                return b;
            }
            case String s -> {
                if ("true".equalsIgnoreCase(s)) return true;
                if ("false".equalsIgnoreCase(s)) return false;
            }
            default -> {
            }
        }
        log.warn("[Quant] data_available期望Boolean实际type={}，默认false", typeName(value));
        return false;
    }

    private String stateString(OverAllState state, String key) {
        Object value = state.value(key).orElse(null);
        switch (value) {
            case null -> {
                return null;
            }
            case String s -> {
                return s;
            }
            case CharSequence cs -> {
                return cs.toString();
            }
            default -> {
            }
        }
        log.warn("[Quant] state key={} 期望String实际type={}，按JSON序列化", key, typeName(value));
        return JSON.toJSONString(value);
    }

    private CryptoAnalysisReport report(Object value) {
        if (value == null) return null;
        if (value instanceof CryptoAnalysisReport report) {
            return report;
        }
        return parseStateObject(value, "report", CryptoAnalysisReport.class);
    }

    private ForecastResult forecastResult(Object value) {
        if (value == null) return null;
        if (value instanceof ForecastResult forecastResult) {
            return forecastResult;
        }
        // GenerateReportNode 写 JSON 字符串，避免 Graph deepCopy 破坏 record；测试/旧路径可能直接放对象。
        return parseStateObject(value, "forecast_result", ForecastResult.class);
    }

    private <T> T parseStateObject(Object value, String key, Class<T> targetType) {
        try {
            String json = value instanceof String s ? s : JSON.toJSONString(value);
            return JSON.parseObject(json, targetType);
        } catch (Exception e) {
            log.warn("[Quant] state key={} 解析失败 type={} target={} msg={}",
                    key, typeName(value), targetType.getSimpleName(), e.toString());
            return null;
        }
    }

    private static String typeName(Object value) {
        return value != null ? value.getClass().getName() : "null";
    }
}
