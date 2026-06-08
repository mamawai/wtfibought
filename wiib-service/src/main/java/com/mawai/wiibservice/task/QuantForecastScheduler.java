package com.mawai.wiibservice.task;

import cn.hutool.json.JSONUtil;
import com.mawai.wiibservice.agent.quant.PriceVolatilitySentinel;
import com.mawai.wiibservice.agent.quant.QuantForecastFacade;
import com.mawai.wiibservice.agent.quant.QuantForecastRunResult;
import com.mawai.wiibservice.agent.quant.domain.ForecastResult;
import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.quant.domain.QuantForecastRequestEvent;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.service.impl.RedisMessageBroadcastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mawai.wiibcommon.constant.QuantConstants;

import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuantForecastScheduler {

    private final QuantForecastFacade quantForecastFacade;
    private final RedisMessageBroadcastService broadcastService;
    private final BinanceRestClient binanceRestClient;
    private final PriceVolatilitySentinel priceVolatilitySentinel;
    private final KlineHistoryStore historyStore;

    /** 每个 symbol 最近成功处理的 5m closeTime。 */
    private final Map<String, Long> lastForecastedCloseTime = new ConcurrentHashMap<>();
    private final Set<String> runningForecastKeys = ConcurrentHashMap.newKeySet();

    /**
     * 主触发：5m KlineClosedEvent → 跑完整 H6/H12/H24 forecast pipeline。
     * 旧 30min cron rollingForecast 废弃，改为事件驱动。
     */
    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (event == null || !"5m".equalsIgnoreCase(event.interval())) return;
        triggerForecast(event.symbol(), event.closeTime(), fetchFearGreedOnce(), "kline_close", false);
    }

    @EventListener
    public void onForecastRequest(QuantForecastRequestEvent event) {
        if (event == null) {
            return;
        }
        String symbol = normalizeSymbol(event.getSymbol());
        long closeTime = resolveCloseTime(symbol, event.getCloseTime());
        triggerForecast(symbol, closeTime, fetchFearGreedOnce(), event.getRequestSource(), event.isForce());
    }

    /**
     * 兜底 cron：每 5 分钟检查是否有新闭合 bar 未处理，防止 WS 落后。
     * 旧 lightRefresh cron 废弃——不再有重/轻周期分离。
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void watchdogFallback() {
        String fearGreedData = fetchFearGreedOnce();
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            String normalized = normalizeSymbol(symbol);
            long now = System.currentTimeMillis();
            Long latestClose = historyStore.latestCloseTime(normalized, KlineHistoryStore.DEFAULT_INTERVAL);
            if (latestClose == null || now - latestClose > Duration.ofMinutes(10).toMillis()) {
                Long latestOpen = historyStore.latestOpenTime(normalized, KlineHistoryStore.DEFAULT_INTERVAL);
                long from = latestOpen != null
                        ? latestOpen + KlineHistoryStore.DEFAULT_BAR_MILLIS
                        : now - Duration.ofDays(1).toMillis();
                historyStore.backfill(normalized, Math.max(0, from), now);
                latestClose = historyStore.latestCloseTime(normalized, KlineHistoryStore.DEFAULT_INTERVAL);
            }
            if (latestClose != null && latestClose > lastForecastedCloseTime.getOrDefault(normalized, 0L)) {
                triggerForecast(normalized, latestClose, fearGreedData, "watchdog", false);
            }
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

    private long resolveCloseTime(String symbol, long requestedCloseTime) {
        if (requestedCloseTime > 0) return requestedCloseTime;
        Long latestClose = historyStore.latestCloseTime(symbol, KlineHistoryStore.DEFAULT_INTERVAL);
        return latestClose != null ? latestClose : System.currentTimeMillis();
    }

    private void triggerForecast(String symbol, long closeTime, String fearGreedData, String source, boolean force) {
        String normalized = normalizeSymbol(symbol);
        closeTime = resolveCloseTime(normalized, closeTime);
        long lastDone = lastForecastedCloseTime.getOrDefault(normalized, 0L);
        if (!force && lastDone >= closeTime) {
            log.info("[Scheduler] 已处理 closeTime 跳过 source={} symbol={} closeTime={}", source, normalized, closeTime);
            return;
        }
        String runningKey = normalized + ":" + closeTime;
        if (!runningForecastKeys.add(runningKey)) {
            log.info("[Scheduler] closeTime处理中跳过 source={} symbol={} closeTime={}", source, normalized, closeTime);
            return;
        }
        long finalCloseTime = closeTime;
        Thread.startVirtualThread(() -> {
            try {
                runForecast(normalized, fearGreedData, finalCloseTime, source);
            } finally {
                runningForecastKeys.remove(runningKey);
            }
        });
    }

    private void runForecast(String symbol, String fearGreedData, long closeTime, String source) {
        log.info("预测开始 symbol={} closeTime={}", symbol, closeTime);
        try {
            QuantForecastRunResult result = quantForecastFacade.run(symbol,
                    source + "-" + symbol + "-" + closeTime,
                    Map.of("fear_greed_data", fearGreedData,
                            "kline_close_time", closeTime,
                            "forecast_source", source));
            if (!result.graphReturned()) {
                log.warn("预测无结果 symbol={}", symbol);
                return;
            }

            if (!result.dataAvailable()) {
                log.warn("预测数据采集失败 symbol={}", symbol);
                return;
            }

            ForecastResult forecastResult = result.forecastResult();
            if (forecastResult != null) {
                // 更新哨兵 ATR 缓存
                if (forecastResult.snapshot() != null) {
                    priceVolatilitySentinel.updateAtr(symbol, forecastResult.snapshot().atr());
                }

                if (result.report() != null) {
                    broadcastService.broadcastQuantSignal(symbol, JSONUtil.toJsonStr(result.report()));
                }

                log.info("预测完成 symbol={} decision={}", symbol, forecastResult.overallDecision());
                // research 完成只标记 forecast 幂等，不发布交易触发事件。
                lastForecastedCloseTime.merge(symbol, closeTime, Math::max);
            } else {
                log.warn("预测无ForecastResult symbol={}", symbol);
            }
        } catch (Exception e) {
            log.error("预测异常 symbol={}", symbol, e);
        }
    }

    //  manual trigger
    public void runForecast(String symbol) {
        Long latestClose = historyStore.latestCloseTime(symbol, KlineHistoryStore.DEFAULT_INTERVAL);
        triggerForecast(symbol, latestClose != null ? latestClose : System.currentTimeMillis(),
                "{}", "manual", true);
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
    }
}
