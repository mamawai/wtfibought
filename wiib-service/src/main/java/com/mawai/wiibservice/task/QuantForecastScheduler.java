package com.mawai.wiibservice.task;

import cn.hutool.json.JSONUtil;
import com.mawai.wiibservice.agent.quant.PriceVolatilitySentinel;
import com.mawai.wiibservice.agent.quant.QuantForecastFacade;
import com.mawai.wiibservice.agent.quant.QuantForecastRunResult;
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
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuantForecastScheduler {

    private final QuantForecastFacade quantForecastFacade;
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

    @Scheduled(cron = "0 */5 * * * *")
    public void lightRefresh() {
        String fearGreedData = fetchFearGreedOnce();
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            // 距离上次重周期不到2分钟跳过：heavy 刚跑完轻周期修正没意义，等下一轮 5min light 再修。
            Instant lastHeavy = lastHeavyCycleTime.get(symbol);
            if (lastHeavy != null && Instant.now().getEpochSecond() - lastHeavy.getEpochSecond() < 120) {
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
            QuantForecastRunResult result = quantForecastFacade.run(symbol, "scheduled-" + symbol, Map.of("fear_greed_data", fearGreedData));
            if (!result.graphReturned()) {
                log.warn("定时预测无结果 symbol={}", symbol);
                return;
            }

            if (!result.dataAvailable()) {
                log.warn("定时预测数据采集失败 symbol={}", symbol);
                return;
            }

            ForecastResult forecastResult = result.forecastResult();
            if (forecastResult != null) {
                // ★ 缓存供轻周期复用
                cacheForLightCycle(symbol, forecastResult, result.rawReportJson());
                lastHeavyCycleTime.put(symbol, Instant.now());

                if (result.report() != null) {
                    broadcastService.broadcastQuantSignal(symbol, JSONUtil.toJsonStr(result.report()));
                }

                log.info("定时预测完成 symbol={} decision={}", symbol, forecastResult.overallDecision());
                eventPublisher.publishEvent(new QuantCycleCompleteEvent(this, symbol, "heavy"));
            } else {
                log.warn("定时预测无ForecastResult symbol={}", symbol);
            }
        } catch (Exception e) {
            log.error("定时预测异常 symbol={}", symbol, e);
        }
    }

    public void runForecast(String symbol) {
        runForecast(symbol, "{}");
    }

    private void cacheForLightCycle(String symbol, ForecastResult forecastResult, String rawReportJson) {
        try {
            List<AgentVote> allVotes = forecastResult.allVotes();
            FeatureSnapshot snapshot = forecastResult.snapshot();
            if (allVotes != null && snapshot != null) {
                lightCycleService.cacheFromHeavyCycle(symbol, forecastResult.cycleId(), allVotes, snapshot,
                        rawReportJson, forecastResult.horizons());
                priceVolatilitySentinel.updateAtr(symbol, snapshot.atr5m());
            }
        } catch (Exception e) {
            log.warn("[Scheduler] 缓存轻周期数据失败 symbol={}: {}", symbol, e.getMessage());
        }
    }
}
