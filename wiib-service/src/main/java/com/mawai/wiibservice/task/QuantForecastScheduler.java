package com.mawai.wiibservice.task;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.fastjson2.JSON;
import com.mawai.wiibservice.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibservice.agent.quant.QuantForecastPersistService;
import com.mawai.wiibservice.agent.quant.domain.ForecastResult;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuantForecastScheduler {

    private final CompiledGraph cryptoAnalysisGraph;
    private final QuantForecastPersistService persistService;
    private final RedisMessageBroadcastService broadcastService;

    private static final List<String> WATCH_LIST = List.of("BTCUSDT");

    @Scheduled(cron = "0 */30 * * * *")
    public void rollingForecast() {
        for (String symbol : WATCH_LIST) {
            Thread.startVirtualThread(() -> runForecast(symbol));
        }
    }

    private void runForecast(String symbol) {
        log.info("定时预测开始 symbol={}", symbol);
        try {
            Optional<OverAllState> result = cryptoAnalysisGraph.invoke(
                    Map.of("target_symbol", symbol),
                    RunnableConfig.builder().threadId("scheduled-" + symbol).build()
            );

            if (result.isEmpty()) {
                log.warn("定时预测无结果 symbol={}", symbol);
                return;
            }

            OverAllState state = result.get();
            boolean dataAvailable = (boolean) state.value("data_available").orElse(true);
            if (!dataAvailable) {
                log.warn("定时预测数据采集失败 symbol={}", symbol);
                return;
            }

            // forecast_result 以 JSON 字符串存入 state，避免框架深拷贝破坏 record 类型
            Object fr = state.value("forecast_result").orElse(null);
            ForecastResult forecastResult = null;
            if (fr instanceof String frJson) {
                forecastResult = JSON.parseObject(frJson, ForecastResult.class);
            } else if (fr instanceof ForecastResult frObj) {
                forecastResult = frObj;
            }
            if (forecastResult != null) {
                String debateSummary = (String) state.value("debate_summary").orElse(null);
                persistService.persist(forecastResult, debateSummary);

                // WebSocket推送
                Object report = state.value("report").orElse(null);
                if (report instanceof CryptoAnalysisReport r) {
                    broadcastService.broadcastQuantSignal(symbol, JSONUtil.toJsonStr(r));
                }

                log.info("定时预测完成 symbol={} decision={}", symbol, forecastResult.overallDecision());
            } else {
                log.warn("定时预测无ForecastResult symbol={} type={}", symbol, fr != null ? fr.getClass().getName() : "null");
            }
        } catch (Exception e) {
            log.error("定时预测异常 symbol={}", symbol, e);
        }
    }
}
