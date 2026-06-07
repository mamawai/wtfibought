package com.mawai.wiibservice.service;

import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class KlineStreamCache {

    private final ApplicationEventPublisher eventPublisher;
    private final KlineHistoryStore historyStore;
    private final ConcurrentMap<String, ClosedBar> latestByKey = new ConcurrentHashMap<>();

    public record ClosedBar(KlineBar bar, long receivedMs) {}

    public void onClosedBar(String symbol, String interval, KlineBar bar) {
        if (symbol == null || symbol.isBlank() || interval == null || interval.isBlank() || bar == null) {
            return;
        }
        String normalizedSymbol = symbol.toUpperCase(Locale.ROOT);
        String normalizedInterval = interval.toLowerCase(Locale.ROOT);
        latestByKey.put(key(normalizedSymbol, normalizedInterval), new ClosedBar(bar, System.currentTimeMillis()));
        persistDefaultInterval(normalizedSymbol, normalizedInterval, bar);
        eventPublisher.publishEvent(new KlineClosedEvent(this, normalizedSymbol, normalizedInterval, bar.closeTime()));
        log.debug("[KlineWS] closed symbol={} interval={} closeTime={}",
                normalizedSymbol, normalizedInterval, bar.closeTime());
    }

    public Optional<KlineBar> latestClosed(String symbol, String interval) {
        ClosedBar closed = latestByKey.get(key(symbol, interval));
        return closed == null ? Optional.empty() : Optional.of(closed.bar());
    }

    public long lastClosedMs(String symbol, String interval) {
        ClosedBar closed = latestByKey.get(key(symbol, interval));
        return closed == null ? 0L : closed.bar().closeTime();
    }

    private void persistDefaultInterval(String symbol, String interval, KlineBar bar) {
        if (!KlineHistoryStore.DEFAULT_INTERVAL.equals(interval)) {
            return;
        }
        try {
            historyStore.saveClosedBar(symbol, interval, bar);
        } catch (Exception e) {
            log.warn("[KlineWS] 5m落库失败 symbol={} closeTime={} msg={}", symbol, bar.closeTime(), e.toString());
        }
    }

    private static String key(String symbol, String interval) {
        String s = symbol == null ? "" : symbol.toUpperCase(Locale.ROOT);
        String i = interval == null ? "" : interval.toLowerCase(Locale.ROOT);
        return s + ":" + i;
    }
}
