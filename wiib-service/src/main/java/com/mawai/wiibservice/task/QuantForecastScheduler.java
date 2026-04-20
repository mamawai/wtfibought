package com.mawai.wiibservice.task;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.fastjson2.JSON;
import com.mawai.wiibservice.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibservice.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibservice.agent.quant.PriceVolatilitySentinel;
import com.mawai.wiibservice.agent.quant.QuantForecastPersistService;
import com.mawai.wiibservice.agent.quant.QuantLightCycleService;
import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibservice.agent.quant.domain.ForecastResult;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mawai.wiibcommon.constant.QuantConstants;

import com.mawai.wiibservice.agent.quant.domain.QuantCycleCompleteEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuantForecastScheduler {

    private final AiAgentRuntimeManager aiAgentRuntimeManager;
    private final QuantForecastPersistService persistService;
    private final RedisMessageBroadcastService broadcastService;
    private final BinanceRestClient binanceRestClient;
    private final QuantLightCycleService lightCycleService;
    private final PriceVolatilitySentinel priceVolatilitySentinel;
    private final ApplicationEventPublisher eventPublisher;

    /** 记录每个symbol最近一次重周期的完成时间 */
    private final Map<String, Instant> lastHeavyCycleTime = new ConcurrentHashMap<>();

    @Scheduled(cron = "0 */30 * * * *")
    public void rollingForecast() {
        String fearGreedData = fetchFearGreedOnce();
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            Thread.startVirtualThread(() -> runForecast(symbol, fearGreedData));
        }
    }

    @Scheduled(cron = "0 */10 * * * *")
    public void lightRefresh() {
        String fearGreedData = fetchFearGreedOnce();
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            // 如果距离上次重周期不到3分钟，跳过（刚跑完重周期没必要立即轻刷）
            Instant lastHeavy = lastHeavyCycleTime.get(symbol);
            if (lastHeavy != null && Instant.now().getEpochSecond() - lastHeavy.getEpochSecond() < 180) {
                log.debug("[Scheduler] 轻周期跳过 symbol={} 距重周期仅{}s",
                        symbol, Instant.now().getEpochSecond() - lastHeavy.getEpochSecond());
                continue;
            }
            if (!lightCycleService.hasCacheFor(symbol)) {
                log.debug("[Scheduler] 轻周期跳过 symbol={} 无重周期缓存", symbol);
                continue;
            }
            Thread.startVirtualThread(() -> lightCycleService.runLightRefresh(symbol, fearGreedData));
        }
    }

    private String fetchFearGreedOnce() {
        try {
            String data = binanceRestClient.getFearGreedIndex(2);
            return (data != null && !data.isBlank()) ? data : "{}";
        } catch (Exception e) {
            log.warn("[Scheduler] FGI预取失败: {}", e.getMessage());
            return "{}";
        }
    }

    private void runForecast(String symbol, String fearGreedData) {
        log.info("定时预测开始 symbol={}", symbol);
        try {
            Optional<OverAllState> result = aiAgentRuntimeManager.invokeQuantWithFallback(
                    symbol, "scheduled-" + symbol,
                    Map.of("fear_greed_data", fearGreedData));

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

            Object fr = state.value("forecast_result").orElse(null);
            ForecastResult forecastResult = null;
            if (fr instanceof String frJson) {
                forecastResult = JSON.parseObject(frJson, ForecastResult.class);
            } else if (fr instanceof ForecastResult frObj) {
                forecastResult = frObj;
            }
            if (forecastResult != null) {
                String debateSummary = (String) state.value("debate_summary").orElse(null);
                String rawSnapshotJson = (String) state.value("raw_snapshot_json").orElse(null);
                String rawReportJson = (String) state.value("raw_report_json").orElse(null);
                persistService.persist(forecastResult, debateSummary, rawSnapshotJson, rawReportJson);

                // ★ 缓存供轻周期复用
                cacheForLightCycle(symbol, state, forecastResult, rawReportJson);
                lastHeavyCycleTime.put(symbol, Instant.now());

                Object report = state.value("report").orElse(null);
                if (report instanceof CryptoAnalysisReport r) {
                    broadcastService.broadcastQuantSignal(symbol, JSONUtil.toJsonStr(r));
                }

                log.info("定时预测完成 symbol={} decision={}", symbol, forecastResult.overallDecision());
                eventPublisher.publishEvent(new QuantCycleCompleteEvent(this, symbol, "heavy"));
            } else {
                log.warn("定时预测无ForecastResult symbol={} type={}", symbol, fr != null ? fr.getClass().getName() : "null");
            }
        } catch (Exception e) {
            log.error("定时预测异常 symbol={}", symbol, e);
        }
    }

    public void runForecast(String symbol) {
        runForecast(symbol, "{}");
    }

    private void cacheForLightCycle(String symbol, OverAllState state,
                                     ForecastResult forecastResult, String rawReportJson) {
        try {
            List<AgentVote> allVotes = forecastResult.allVotes();
            FeatureSnapshot snapshot = forecastResult.snapshot();
            if (allVotes != null && snapshot != null) {
                lightCycleService.cacheFromHeavyCycle(symbol, allVotes, snapshot, rawReportJson, forecastResult.horizons());
                priceVolatilitySentinel.updateAtr(symbol, snapshot.atr5m());
            }
        } catch (Exception e) {
            log.warn("[Scheduler] 缓存轻周期数据失败 symbol={}: {}", symbol, e.getMessage());
        }
    }
}
