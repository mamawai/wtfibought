package com.mawai.wiibquant.task;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.quant.PriceVolatilitySentinel;
import com.mawai.wiibquant.agent.quant.QuantSnapshotGraphFactory;
import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibquant.agent.quant.domain.QuantForecastRequestEvent;
import com.mawai.wiibquant.agent.toolkit.MarketAssembly;
import com.mawai.wiibquant.agent.toolkit.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数值快照调度器（P2a）：每根 5m bar 收盘跑一次零 LLM 快照图。
 * 触发面继承旧 QuantForecastScheduler：5m 事件驱动为主 + watchdog cron 兜底 + 哨兵/手动事件；
 * closeTime 幂等去重 + runningKey 防并发。深研判触发（1h 定频/插队门控）P2b 在此之上加 trigger 判定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuantSnapshotScheduler {

    private final QuantSnapshotGraphFactory graphFactory;
    private final MarketDataService marketDataService;
    private final PriceVolatilitySentinel priceVolatilitySentinel;
    private final KlineHistoryStore historyStore;

    /** 每个 symbol 最近成功处理的 5m closeTime。 */
    private final Map<String, Long> lastSnapshotCloseTime = new ConcurrentHashMap<>();
    private final Set<String> runningKeys = ConcurrentHashMap.newKeySet();

    /** 主触发：5m K 线收盘。 */
    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (event == null || !"5m".equalsIgnoreCase(event.interval())) return;
        triggerSnapshot(event.symbol(), event.closeTime(), "kline_close", false);
    }

    /** 哨兵价格异动 / 手动请求事件。 */
    @EventListener
    public void onForecastRequest(QuantForecastRequestEvent event) {
        if (event == null) return;
        String symbol = normalizeSymbol(event.getSymbol());
        long closeTime = resolveCloseTime(symbol, event.getCloseTime());
        triggerSnapshot(symbol, closeTime, event.getRequestSource(), event.isForce());
    }

    /** 兜底 cron：每 5 分钟检查是否有新闭合 bar 未处理，防止 WS 落后。 */
    @Scheduled(cron = "0 */5 * * * *")
    public void watchdogFallback() {
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
            if (latestClose != null && latestClose > lastSnapshotCloseTime.getOrDefault(normalized, 0L)) {
                triggerSnapshot(normalized, latestClose, "watchdog", false);
            }
        }
    }

    /** 手动触发（Controller 用）。 */
    public void runSnapshot(String symbol) {
        String normalized = normalizeSymbol(symbol);
        Long latestClose = historyStore.latestCloseTime(normalized, KlineHistoryStore.DEFAULT_INTERVAL);
        triggerSnapshot(normalized, latestClose != null ? latestClose : System.currentTimeMillis(),
                "manual", true);
    }

    private void triggerSnapshot(String symbol, long closeTime, String source, boolean force) {
        String normalized = normalizeSymbol(symbol);
        long resolved = resolveCloseTime(normalized, closeTime);
        long lastDone = lastSnapshotCloseTime.getOrDefault(normalized, 0L);
        if (!force && lastDone >= resolved) {
            log.info("[Snapshot] 已处理 closeTime 跳过 source={} symbol={} closeTime={}", source, normalized, resolved);
            return;
        }
        String runningKey = normalized + ":" + resolved;
        if (!runningKeys.add(runningKey)) {
            log.info("[Snapshot] closeTime处理中跳过 source={} symbol={} closeTime={}", source, normalized, resolved);
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                runOnce(normalized, resolved, source);
            } finally {
                runningKeys.remove(runningKey);
            }
        });
    }

    private void runOnce(String symbol, long closeTime, String source) {
        log.info("[Snapshot] 快照开始 symbol={} closeTime={} source={}", symbol, closeTime, source);
        try {
            var out = graphFactory.get().invoke(Map.of(
                    "target_symbol", symbol,
                    "kline_close_time", closeTime,
                    "trigger_source", source));
            Long snapshotId = out.flatMap(s -> s.value("snapshot_id"))
                    .filter(Number.class::isInstance).map(v -> ((Number) v).longValue())
                    .orElse(null);
            if (snapshotId == null) {
                log.warn("[Snapshot] 未产出快照 symbol={} closeTime={}", symbol, closeTime);
                return;
            }
            // 喂哨兵 ATR（组装结果在 MarketDataService 60s 缓存内，零成本取）
            MarketAssembly assembly = marketDataService.assemble(symbol);
            if (assembly.available() && assembly.snapshot().atr() != null) {
                priceVolatilitySentinel.updateAtr(symbol, assembly.snapshot().atr());
            }
            lastSnapshotCloseTime.merge(symbol, closeTime, Math::max);
            log.info("[Snapshot] 快照完成 symbol={} id={} fragility={}", symbol, snapshotId,
                    assembly.available() ? assembly.fragility().score() : -1);
        } catch (Exception e) {
            log.error("[Snapshot] 快照异常 symbol={}", symbol, e);
        }
    }

    private long resolveCloseTime(String symbol, long requestedCloseTime) {
        if (requestedCloseTime > 0) return requestedCloseTime;
        Long latestClose = historyStore.latestCloseTime(symbol, KlineHistoryStore.DEFAULT_INTERVAL);
        return latestClose != null ? latestClose : System.currentTimeMillis();
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
    }
}
