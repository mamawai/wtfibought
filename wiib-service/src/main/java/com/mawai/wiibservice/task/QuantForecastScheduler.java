package com.mawai.wiibservice.task;

import cn.hutool.json.JSONUtil;
import com.mawai.wiibservice.agent.quant.PriceVolatilitySentinel;
import com.mawai.wiibservice.agent.quant.QuantForecastFacade;
import com.mawai.wiibservice.agent.quant.QuantForecastRunResult;
import com.mawai.wiibservice.agent.quant.QuantLightCycleService;
import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibservice.agent.quant.domain.ForecastResult;
import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mawai.wiibcommon.constant.QuantConstants;

import com.mawai.wiibservice.agent.quant.domain.QuantCycleCompleteEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuantForecastScheduler {

    private static final Duration LIGHT_TRIGGER_DEDUP_WINDOW = Duration.ofSeconds(60);

    private final QuantForecastFacade quantForecastFacade;
    private final RedisMessageBroadcastService broadcastService;
    private final BinanceRestClient binanceRestClient;
    private final QuantLightCycleService lightCycleService;
    private final PriceVolatilitySentinel priceVolatilitySentinel;
    private final ApplicationEventPublisher eventPublisher;

    /** 记录每个symbol最近一次重周期的完成时间 */
    private final Map<String, Instant> lastHeavyCycleTime = new ConcurrentHashMap<>();

    /** 记录每个symbol最近一次轻周期触发时间，防 cron 与 5m 收盘事件同分钟重复触发 */
    private final Map<String, Instant> lastLightCycleTriggerTime = new ConcurrentHashMap<>();

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
            triggerLightRefresh(symbol, fearGreedData, "cron");
        }
    }

    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (event == null || !"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        triggerLightRefresh(event.symbol(), fetchFearGreedOnce(), "kline_close");
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
        String normalized = normalizeSymbol(symbol);
        log.info("定时预测开始 symbol={}", normalized);
        try {
            QuantForecastRunResult result = quantForecastFacade.run(normalized, "scheduled-" + normalized, Map.of("fear_greed_data", fearGreedData));
            if (!result.graphReturned()) {
                log.warn("定时预测无结果 symbol={}", normalized);
                return;
            }

            if (!result.dataAvailable()) {
                log.warn("定时预测数据采集失败 symbol={}", normalized);
                return;
            }

            ForecastResult forecastResult = result.forecastResult();
            if (forecastResult != null) {
                // ★ 缓存供轻周期复用
                cacheForLightCycle(normalized, forecastResult, result.rawReportJson());
                lastHeavyCycleTime.put(normalized, Instant.now());

                if (result.report() != null) {
                    broadcastService.broadcastQuantSignal(normalized, JSONUtil.toJsonStr(result.report()));
                }

                log.info("定时预测完成 symbol={} decision={}", normalized, forecastResult.overallDecision());
                eventPublisher.publishEvent(new QuantCycleCompleteEvent(this, normalized, "heavy"));
            } else {
                log.warn("定时预测无ForecastResult symbol={}", normalized);
            }
        } catch (Exception e) {
            log.error("定时预测异常 symbol={}", normalized, e);
        }
    }

    public void runForecast(String symbol) {
        runForecast(symbol, "{}");
    }

    private void triggerLightRefresh(String symbol, String fearGreedData, String source) {
        String normalized = normalizeSymbol(symbol);
        Instant now = Instant.now();
        // 距离上次重周期不到2分钟跳过：heavy 刚跑完轻周期修正没意义，等下一轮 5min light 再修。
        Instant lastHeavy = lastHeavyCycleTime.get(normalized);
        if (lastHeavy != null && now.getEpochSecond() - lastHeavy.getEpochSecond() < 120) {
            log.debug("[Scheduler] 轻周期跳过 source={} symbol={} 距重周期仅{}s",
                    source, normalized, now.getEpochSecond() - lastHeavy.getEpochSecond());
            return;
        }
        if (!lightCycleService.hasCacheFor(normalized)) {
            log.debug("[Scheduler] 轻周期跳过 source={} symbol={} 无重周期缓存", source, normalized);
            return;
        }
        boolean accepted = markLightTriggerIfDue(normalized, now);
        if (!accepted) {
            log.debug("[Scheduler] 轻周期跳过 source={} symbol={} 触发间隔小于{}s",
                    source, normalized, LIGHT_TRIGGER_DEDUP_WINDOW.toSeconds());
            return;
        }
        Thread.startVirtualThread(() -> lightCycleService.runLightRefresh(normalized, fearGreedData));
    }

    private boolean markLightTriggerIfDue(String symbol, Instant now) {
        boolean[] accepted = {false};
        lastLightCycleTriggerTime.compute(symbol, (key, previous) -> {
            if (previous != null
                    && Duration.between(previous, now).compareTo(LIGHT_TRIGGER_DEDUP_WINDOW) < 0) {
                return previous;
            }
            accepted[0] = true;
            return now;
        });
        return accepted[0];
    }

    private void cacheForLightCycle(String symbol, ForecastResult forecastResult, String rawReportJson) {
        try {
            List<AgentVote> allVotes = forecastResult.allVotes();
            FeatureSnapshot snapshot = forecastResult.snapshot();
            if (allVotes != null && snapshot != null) {
                lightCycleService.cacheFromHeavyCycle(symbol, forecastResult.cycleId(), allVotes, snapshot,
                        rawReportJson, forecastResult.horizons());
                priceVolatilitySentinel.updateAtr(symbol, snapshot.atr());
            }
        } catch (Exception e) {
            log.warn("[Scheduler] 缓存轻周期数据失败 symbol={}: {}", symbol, e.getMessage());
        }
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
    }
}
