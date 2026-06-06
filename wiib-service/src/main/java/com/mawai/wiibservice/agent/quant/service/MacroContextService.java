package com.mawai.wiibservice.agent.quant.service;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.quant.domain.MacroContext;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.MultiOutputForecast;
import com.mawai.wiibservice.agent.research.forecast.QuantCoreForecaster;
import com.mawai.wiibservice.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibservice.agent.research.kline.KlineAggregator;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MacroContextService {

    static final String INTERVAL_1M = "1m";
    static final long ONE_HOUR_MS = 3_600_000L;
    static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration HISTORY = Duration.ofDays(90);
    private static final Duration MIN_HISTORY = Duration.ofDays(30);
    private static final Duration FRESH_TAIL = Duration.ofMinutes(10);
    private static final Duration MAX_SYNC_TAIL = Duration.ofHours(6);

    private final KlineHistoryStore historyStore;
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
        recompute(normalized, false);
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
        Thread.startVirtualThread(() -> recompute(normalized, allowLongBackfill));
    }

    private void recompute(String symbol, boolean allowLongBackfill) {
        if (!recomputing.add(symbol)) {
            return;
        }
        long startMs = System.currentTimeMillis();
        boolean scheduleLongBackfill = false;
        try {
            long now = System.currentTimeMillis();
            if (!ensureHistoryReady(symbol, now, allowLongBackfill)) {
                scheduleLongBackfill = !allowLongBackfill;
                cache.putIfAbsent(symbol, MacroContext.neutral(symbol));
                return;
            }
            long from = now - HISTORY.toMillis();
            List<KlineBar> oneMin = historyStore.load(symbol, INTERVAL_1M, from, now);
            List<KlineBar> bars1h = KlineAggregator.aggregate(oneMin, ONE_HOUR_MS);
            if (bars1h.size() < MIN_HISTORY.toHours()) {
                cache.put(symbol, new MacroContext(symbol, Instant.now(), Map.of(), true,
                        List.of("MACRO_HISTORY_SHORT")));
                log.warn("[MacroContext] history short symbol={} oneMin={} bars1h={}",
                        symbol, oneMin.size(), bars1h.size());
                return;
            }

            ResearchFeatures features = new ResearchFeatures(bars1h, 0.0, 50, 0.0, 0.0);
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
            MacroContext context = new MacroContext(symbol, Instant.now(), legs, false, List.of());
            cache.put(symbol, context);
            log.info("[MacroContext] refreshed symbol={} bars1h={} risk={} cost={}ms",
                    symbol, bars1h.size(), context.toRiskHint(), System.currentTimeMillis() - startMs);
        } catch (Exception e) {
            cache.putIfAbsent(symbol, MacroContext.neutral(symbol));
            log.warn("[MacroContext] refresh failed symbol={} msg={}", symbol, e.toString());
        } finally {
            recomputing.remove(symbol);
            if (scheduleLongBackfill) {
                refreshAsync(symbol, true);
            }
        }
    }

    private boolean ensureHistoryReady(String symbol, long now, boolean allowLongBackfill) {
        Long latest = historyStore.latestOpenTime(symbol, INTERVAL_1M);
        long from = now - HISTORY.toMillis();
        if (latest == null) {
            if (!allowLongBackfill) {
                return false;
            }
            historyStore.backfill(symbol, from, now);
            return historyStore.latestOpenTime(symbol, INTERVAL_1M) != null;
        }

        long lag = now - latest;
        if (lag <= FRESH_TAIL.toMillis()) {
            return true;
        }
        if (lag > MAX_SYNC_TAIL.toMillis() && !allowLongBackfill) {
            return false;
        }
        long tailFrom = Math.max(from, latest + 60_000L);
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
