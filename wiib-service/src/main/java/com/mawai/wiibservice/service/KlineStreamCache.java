package com.mawai.wiibservice.service;

import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

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
        latestByKey.put(key(symbol, interval), new ClosedBar(bar, System.currentTimeMillis()));
        if (KlineHistoryStore.DEFAULT_INTERVAL.equals(interval)) {
            // 异步落库，不占WS回调线程；下游预测管线经HTTP采集(秒级)后才读historyStore，无竞态
            Thread.startVirtualThread(() -> persistDefaultInterval(symbol, interval, bar));
        }
        eventPublisher.publishEvent(new KlineClosedEvent(this, symbol, interval, bar.closeTime()));
        log.info("[KlineWS] closed symbol={} interval={} closeTime={}", symbol, interval, bar.closeTime());
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
        String s = symbol == null ? "" : symbol;
        String i = interval == null ? "" : interval;
        return s + ":" + i;
    }
}
