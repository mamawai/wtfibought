package com.mawai.wiibquant.task;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.quant.PriceVolatilitySentinel;
import com.mawai.wiibquant.agent.quant.QuantSnapshotGraphFactory;
import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibquant.agent.quant.domain.QuantForecastRequestEvent;
import com.mawai.wiibquant.agent.toolkit.MarketAssembly;
import com.mawai.wiibquant.agent.toolkit.MarketDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时轨调度器（P2b 完整形态）：每根 5m bar 收盘跑快照图；深研判门控在调度层判定——
 * 1h 定频基线（配置可调）+ 哨兵/手动插队（带冷却防抖），trigger_deep 随 state 进图由 gate 条件边分流。
 * closeTime 幂等去重 + runningKey 防并发 + 深研判在飞标记防双烧（记账在完成后，标记堵并发窗口）。
 */
@Slf4j
@Component
public class QuantSnapshotScheduler {

    private final QuantSnapshotGraphFactory graphFactory;
    private final MarketDataService marketDataService;
    private final PriceVolatilitySentinel priceVolatilitySentinel;
    private final KlineHistoryStore historyStore;
    /** 深研判定频基线：默认 1h（研判对象是 H6/H12/H24，30min 重采样内容重复度高） */
    private final long deepIntervalMs;
    /** 插队冷却：哨兵/手动触发后，冷却期内的再次插队只产快照不烧 LLM */
    private final long deepCooldownMs;

    public QuantSnapshotScheduler(QuantSnapshotGraphFactory graphFactory,
                                  MarketDataService marketDataService,
                                  PriceVolatilitySentinel priceVolatilitySentinel,
                                  KlineHistoryStore historyStore,
                                  @Value("${quant.deep-analysis.interval-ms:3600000}") long deepIntervalMs,
                                  @Value("${quant.deep-analysis.cooldown-ms:900000}") long deepCooldownMs) {
        this.graphFactory = graphFactory;
        this.marketDataService = marketDataService;
        this.priceVolatilitySentinel = priceVolatilitySentinel;
        this.historyStore = historyStore;
        this.deepIntervalMs = deepIntervalMs;
        this.deepCooldownMs = deepCooldownMs;
    }

    /** 每个 symbol 最近成功处理的 5m closeTime。 */
    private final Map<String, Long> lastSnapshotCloseTime = new ConcurrentHashMap<>();
    /** 每个 symbol 最近一次深研判墙钟时间（定频与冷却共用一本账）。 */
    private final Map<String, Long> lastDeepAnalysisAt = new ConcurrentHashMap<>();
    private final Set<String> runningKeys = ConcurrentHashMap.newKeySet();
    /** 深研判在飞标记：LLM 跑几十秒到几分钟、完成后才记账，这段窗口内并发触发只产快照不烧 LLM。 */
    private final Set<String> deepInFlight = ConcurrentHashMap.newKeySet();

    /** 主触发：5m K 线收盘。 */
    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!"5m".equalsIgnoreCase(event.interval())) return;
        // 快照/研判轨只跑 WATCH_SYMBOLS：策略篮子币(SOL/XRP等)的 bar 不进本轨，防深研判 LLM 成本随行情币扩容翻倍
        if (!QuantConstants.WATCH_SYMBOLS.contains(QuantConstants.normalizeSymbolLenient(event.symbol()))) return;
        triggerSnapshot(event.symbol(), event.closeTime(), "kline_close", false);
    }

    /** 哨兵价格异动 / 手动请求事件；归一化与 closeTime 解析统一在 triggerSnapshot 做一次。 */
    @EventListener
    public void onForecastRequest(QuantForecastRequestEvent event) {
        triggerSnapshot(event.getSymbol(), event.getCloseTime(), event.getRequestSource(), event.isForce());
    }

    /** 兜底 cron：每 5 分钟检查是否有新闭合 bar 未处理，防止 WS 落后。 */
    @Scheduled(cron = "0 */5 * * * *")
    public void watchdogFallback() {
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            String normalized = QuantConstants.normalizeSymbolLenient(symbol);
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

    /** 手动触发（Controller 用）；closeTime 传 0 由 triggerSnapshot 解析到最新闭合 bar。 */
    public void runSnapshot(String symbol) {
        triggerSnapshot(symbol, 0L, "manual", true);
    }

    private void triggerSnapshot(String symbol, long closeTime, String source, boolean force) {
        String normalized = QuantConstants.normalizeSymbolLenient(symbol);
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
        boolean triggerDeep = decideDeepTrigger(symbol, source);
        // 账本读的是"已完成"的深研判；在飞的靠标记补位，抢不到就本次降级纯快照（快照时序不受影响）
        boolean deepClaimed = triggerDeep && deepInFlight.add(symbol);
        if (triggerDeep && !deepClaimed) {
            log.info("[Snapshot] 深研判在飞，本次降级纯快照 symbol={} source={}", symbol, source);
            triggerDeep = false;
        }
        log.info("[Snapshot] 快照开始 symbol={} closeTime={} source={} deep={}", symbol, closeTime, source, triggerDeep);
        try {
            var out = graphFactory.get().invoke(Map.of(
                    "target_symbol", symbol,
                    "kline_close_time", closeTime,
                    "trigger_source", source,
                    "trigger_deep", triggerDeep));
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
            Long analysisId = out.flatMap(s -> s.value("analysis_id"))
                    .filter(Number.class::isInstance).map(v -> ((Number) v).longValue())
                    .orElse(null);
            if (analysisId != null) {
                // 深研判成功才记账：LLM 失败缺席的轮次不消耗定频窗口，下根 bar 会再试
                lastDeepAnalysisAt.put(symbol, System.currentTimeMillis());
            }
            log.info("[Snapshot] 快照完成 symbol={} snapshotId={} analysisId={} fragility={}",
                    symbol, snapshotId, analysisId,
                    assembly.available() ? assembly.fragility().score() : -1);
        } catch (Exception e) {
            log.error("[Snapshot] 快照异常 symbol={}", symbol, e);
        } finally {
            if (deepClaimed) {
                deepInFlight.remove(symbol);
            }
        }
    }

    /**
     * 深研判门控（确定性，不烧 LLM 路由）：
     * manual/sentinel 插队但受冷却约束；常规 bar（kline_close/watchdog）按 1h 定频基线。
     * 只查账本；在飞并发窗口由 runOnce 的 deepInFlight 标记兜底。
     */
    private boolean decideDeepTrigger(String symbol, String source) {
        long now = System.currentTimeMillis();
        long last = lastDeepAnalysisAt.getOrDefault(symbol, 0L);
        boolean isJump = "manual".equals(source) || "sentinel".equals(source);
        if (isJump) {
            boolean cooled = now - last >= deepCooldownMs;
            if (!cooled) {
                log.info("[Snapshot] 深研判冷却中跳过插队 symbol={} source={} 距上次{}s",
                        symbol, source, (now - last) / 1000);
            }
            return cooled;
        }
        return now - last >= deepIntervalMs;
    }

    private long resolveCloseTime(String symbol, long requestedCloseTime) {
        if (requestedCloseTime > 0) return requestedCloseTime;
        Long latestClose = historyStore.latestCloseTime(symbol, KlineHistoryStore.DEFAULT_INTERVAL);
        return latestClose != null ? latestClose : System.currentTimeMillis();
    }
}
