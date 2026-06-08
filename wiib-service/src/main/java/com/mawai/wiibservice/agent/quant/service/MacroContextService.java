package com.mawai.wiibservice.agent.quant.service;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.quant.domain.MacroContext;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.MultiOutputForecast;
import com.mawai.wiibservice.agent.research.forecast.QuantCoreForecaster;
import com.mawai.wiibservice.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MacroContextService {

    static final String INTERVAL_5M = KlineHistoryStore.DEFAULT_INTERVAL;
    static final long FIVE_MINUTE_MS = KlineHistoryStore.DEFAULT_BAR_MILLIS;
    static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration HISTORY = Duration.ofDays(90);
    private static final Duration MIN_HISTORY = Duration.ofDays(30);
    private static final int MIN_HISTORY_BARS = Math.toIntExact(MIN_HISTORY.toMillis() / FIVE_MINUTE_MS);
    private static final Duration FRESH_TAIL = Duration.ofMinutes(10);
    private static final Duration MAX_SYNC_TAIL = Duration.ofHours(6);

    private final KlineHistoryStore historyStore;
    private final ResearchFeatureAssembler featureAssembler;
    private final Map<String, MacroContext> cache = new ConcurrentHashMap<>();
    private final Set<String> recomputing = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void warmup() {
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            refreshAsync(symbol, true);
        }
    }

    public MacroContext get(String symbol) {
        String normalized = normalize(symbol);
        MacroContext current = cache.get(normalized);
        if (current == null) {
            refreshAsync(normalized, true);
            return MacroContext.neutral(normalized);
        }
        if (expired(current)) {
            refreshAsync(normalized, false);
            return current.withStale(true);
        }
        return current;
    }

    public void refreshIfStale(String symbol) {
        String normalized = normalize(symbol);
        MacroContext current = cache.get(normalized);
        if (current != null && !expired(current)) {
            return;
        }
        recompute(normalized, System.currentTimeMillis(), false);
    }

    /**
     * live workflow 主入口：按本轮 5m closeTime 同步计算 H6/H12/H24。
     * 这里不走 TTL，避免主链路拿到 30min 旧 research 结果。
     */
    public MacroContext computeNow(String symbol, long decisionCloseTime) {
        String normalized = normalize(symbol);
        long toTime = decisionCloseTime > 0 ? decisionCloseTime + 1 : System.currentTimeMillis();
        MacroContext context = recompute(normalized, toTime, true);
        return context != null ? context : cache.getOrDefault(normalized, MacroContext.neutral(normalized));
    }

    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (event == null || !"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        String normalized = normalize(event.symbol());
        MacroContext current = cache.get(normalized);
        if (current != null && !expired(current)) {
            return;
        }
        refreshAsync(normalized, false);
    }

    private void refreshAsync(String symbol, boolean allowLongBackfill) {
        String normalized = normalize(symbol);
        Thread.startVirtualThread(() -> recompute(normalized, System.currentTimeMillis(), allowLongBackfill));
    }

    private MacroContext recompute(String symbol, long toTime, boolean allowLongBackfill) {
        if (!recomputing.add(symbol)) {
            return cache.get(symbol);
        }
        long startMs = System.currentTimeMillis();
        boolean scheduleLongBackfill = false;
        try {
            if (!ensureHistoryReady(symbol, toTime, allowLongBackfill)) {
                scheduleLongBackfill = !allowLongBackfill;
                MacroContext neutral = MacroContext.neutral(symbol);
                cache.putIfAbsent(symbol, neutral);
                return cache.get(symbol);
            }
            long from = toTime - HISTORY.toMillis();
            List<KlineBar> bars5m = historyStore.load(symbol, INTERVAL_5M, from, toTime);
            if (bars5m.size() < MIN_HISTORY_BARS) {
                MacroContext shortHistory = new MacroContext(symbol, Instant.now(), Map.of(), true,
                        List.of("MACRO_HISTORY_SHORT"));
                cache.put(symbol, shortHistory);
                log.warn("[MacroContext] history short symbol={} bars5m={} required={}",
                        symbol, bars5m.size(), MIN_HISTORY_BARS);
                return shortHistory;
            }

            // 5m 是当前决策粒度；H6/H12/H24 只改变预测窗口，不再先压成 1h。
            // 外生因子(funding/FGI/ETF/stablecoin)按最新bar closeTime 对齐，缺数据自动fallback中性+flag
            ResearchFeatureAssembler.AssemblyResult assembled = featureAssembler.assemble(symbol, bars5m);
            ResearchFeatures features = assembled.features();
            List<String> qualityFlags = new ArrayList<>();
            qualityFlags.addAll(assembled.qualityFlags());

            Map<ForecastHorizon, MacroContext.Leg> legs = new EnumMap<>(ForecastHorizon.class);
            for (ForecastHorizon horizon : ForecastHorizon.values()) {
                MultiOutputForecast forecast = QuantCoreForecaster.defaults(horizon).forecast(features);
                var vol = forecast.volatilityContext();
                var direction = forecast.direction();
                legs.put(horizon, new MacroContext.Leg(
                        vol.riskTier(),
                        vol.riskBudgetHint(),
                        vol.expectedMoveBps(),
                        vol.trailingPercentile(),
                        forecast.regime(),
                        forecast.regimeConfidence(),
                        direction.direction(),
                        direction.confidence()));
            }
            MacroContext context = new MacroContext(symbol, Instant.now(), legs, false, qualityFlags);
            cache.put(symbol, context);
            log.info("[MacroContext] refreshed symbol={} bars5m={} risk={} cost={}ms",
                    symbol, bars5m.size(), context.toRiskHint(), System.currentTimeMillis() - startMs);
            return context;
        } catch (Exception e) {
            MacroContext neutral = MacroContext.neutral(symbol);
            cache.putIfAbsent(symbol, neutral);
            log.warn("[MacroContext] refresh failed symbol={} msg={}", symbol, e.toString());
            return cache.get(symbol);
        } finally {
            recomputing.remove(symbol);
            if (scheduleLongBackfill) {
                refreshAsync(symbol, true);
            }
        }
    }

    private boolean ensureHistoryReady(String symbol, long now, boolean allowLongBackfill) {
        Long latest = historyStore.latestOpenTime(symbol, INTERVAL_5M);
        long from = now - HISTORY.toMillis();
        if (latest == null) {
            if (!allowLongBackfill) {
                return false;
            }
            historyStore.backfill(symbol, from, now);
            return historyStore.latestOpenTime(symbol, INTERVAL_5M) != null;
        }

        long lag = now - latest;
        if (lag <= FRESH_TAIL.toMillis()) {
            return true;
        }
        if (lag > MAX_SYNC_TAIL.toMillis() && !allowLongBackfill) {
            return false;
        }
        long tailFrom = Math.max(from, latest + FIVE_MINUTE_MS);
        historyStore.backfill(symbol, tailFrom, now);
        return true;
    }

    private boolean expired(MacroContext context) {
        if (context == null || context.computedAt() == null || context.computedAt().equals(Instant.EPOCH)) {
            return true;
        }
        return Duration.between(context.computedAt(), Instant.now()).compareTo(TTL) >= 0;
    }

    private static String normalize(String symbol) {
        return symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
    }
}
