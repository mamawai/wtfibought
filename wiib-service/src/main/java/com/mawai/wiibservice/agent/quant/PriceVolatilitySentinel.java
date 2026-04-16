package com.mawai.wiibservice.agent.quant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.task.AiTradingScheduler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 价格波动哨兵：监听 WS 实时 markPrice tick，当5分钟内价格变动超过 1.3×ATR-5m 时
 * 触发「轻周期刷新 + AI交易」，捕捉异常波动中的交易机会。
 * <p>
 * ATR-5m 由量化管道（重周期/轻周期）计算后推送更新；启动时从DB加载最近一次ATR。
 * 冷却期3分钟，防止高波动市场下过度触发。
 */
@Slf4j
@Component
public class PriceVolatilitySentinel {

    private static final double ATR_MULTIPLIER = 1.3;
    private static final long COOLDOWN_MS = 30 * 1000L; // 30秒技术防抖
    private static final long WINDOW_MS = 5 * 60 * 1000L;
    private static final long SAMPLE_INTERVAL_MS = 1000L;

    /** 兜底固定阈值：ATR 尚未就绪时使用 */
    private static final Map<String, Double> FALLBACK_THRESHOLDS = Map.of(
            "BTCUSDT", 0.003,
            "ETHUSDT", 0.005,
            "PAXGUSDT", 0.002
    );
    private static final double DEFAULT_FALLBACK = 0.004;

    private final QuantLightCycleService lightCycleService;
    private final AiTradingScheduler aiTradingScheduler;
    private final BinanceRestClient binanceRestClient;
    private final QuantForecastCycleMapper cycleMapper;

    private final Map<String, Deque<PriceTick>> priceWindows = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastAtr5m = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTriggerTime = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSampleTime = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> triggerRunning = new ConcurrentHashMap<>();

    record PriceTick(long timestampMs, BigDecimal price) {}

    public PriceVolatilitySentinel(QuantLightCycleService lightCycleService,
                                   AiTradingScheduler aiTradingScheduler,
                                   BinanceRestClient binanceRestClient,
                                   QuantForecastCycleMapper cycleMapper) {
        this.lightCycleService = lightCycleService;
        this.aiTradingScheduler = aiTradingScheduler;
        this.binanceRestClient = binanceRestClient;
        this.cycleMapper = cycleMapper;
    }

    @PostConstruct
    void initAtrFromDb() {
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            try {
                QuantForecastCycle cycle = cycleMapper.selectLatest(symbol);
                if (cycle == null || cycle.getSnapshotJson() == null) continue;
                if (cycle.getForecastTime() != null
                        && cycle.getForecastTime().isBefore(LocalDateTime.now().minusMinutes(30))) {
                    log.info("[Sentinel] DB中ATR已过期，跳过 symbol={} forecastTime={}", symbol, cycle.getForecastTime());
                    continue;
                }
                JSONObject snap = JSON.parseObject(cycle.getSnapshotJson());
                Object atrVal = snap.get("atr5m");
                if (atrVal == null) continue;
                BigDecimal atr = new BigDecimal(atrVal.toString());
                if (atr.compareTo(BigDecimal.ZERO) > 0) {
                    lastAtr5m.put(symbol, atr);
                    log.info("[Sentinel] 从DB加载ATR symbol={} atr5m={}", symbol, atr);
                }
            } catch (Exception e) {
                log.warn("[Sentinel] 加载ATR失败 symbol={}: {}", symbol, e.getMessage());
            }
        }
    }

    /**
     * 由量化管道（重/轻周期）完成后调用，更新 ATR-5m 基准
     */
    public void updateAtr(String symbol, BigDecimal atr5m) {
        if (atr5m != null && atr5m.compareTo(BigDecimal.ZERO) > 0) {
            lastAtr5m.put(symbol, atr5m);
            log.debug("[Sentinel] ATR更新 symbol={} atr5m={}", symbol, atr5m);
        }
    }

    /**
     * 每个 markPrice tick 调用（@1s），由 BinanceWsClient 驱动。
     * 内部降采样到每1秒一次检查
     */
    public void onPriceTick(String symbol, BigDecimal markPrice) {
        if (!QuantConstants.ALLOWED_SYMBOLS.contains(symbol)) return;

        long now = System.currentTimeMillis();

        // 降采样：每1秒处理一次
        Long lastSample = lastSampleTime.get(symbol);
        if (lastSample != null && now - lastSample < SAMPLE_INTERVAL_MS) return;
        lastSampleTime.put(symbol, now);

        // 维护5分钟滚动窗口
        Deque<PriceTick> window = priceWindows.computeIfAbsent(symbol, k -> new ConcurrentLinkedDeque<>());
        window.addLast(new PriceTick(now, markPrice));

        // 清理过期 tick
        long cutoff = now - WINDOW_MS;
        while (!window.isEmpty() && window.peekFirst().timestampMs() < cutoff) {
            window.pollFirst();
        }

        if (window.size() < 2) return;

        // 冷却期内不检查
        Long lastTrigger = lastTriggerTime.get(symbol);
        if (lastTrigger != null && now - lastTrigger < COOLDOWN_MS) return;

        // 计算窗口内最大波动幅度
        BigDecimal windowHigh = markPrice;
        BigDecimal windowLow = markPrice;
        for (PriceTick tick : window) {
            if (tick.price().compareTo(windowHigh) > 0) windowHigh = tick.price();
            if (tick.price().compareTo(windowLow) < 0) windowLow = tick.price();
        }

        BigDecimal priceRange = windowHigh.subtract(windowLow);
        BigDecimal midPrice = windowHigh.add(windowLow).divide(BigDecimal.TWO, 8, RoundingMode.HALF_UP);
        if (midPrice.compareTo(BigDecimal.ZERO) <= 0) return;

        double rangePct = priceRange.doubleValue() / midPrice.doubleValue();

        // 计算动态阈值
        double threshold = computeThreshold(symbol, midPrice);
        if (rangePct < threshold) return;

        // 触发！防重入
        AtomicBoolean running = triggerRunning.computeIfAbsent(symbol, k -> new AtomicBoolean(false));
        if (!running.compareAndSet(false, true)) return;

        lastTriggerTime.put(symbol, now);
        log.info("[Sentinel] ⚡ 波动触发 symbol={} range={} ({}) threshold={} window={}ticks",
                symbol, String.format("%.2f", rangePct * 100) + "%",
                priceRange.toPlainString(), String.format("%.2f", threshold * 100) + "%",
                window.size());

        Thread.startVirtualThread(() -> {
            try {
                executeVolatilityResponse(symbol);
            } finally {
                running.set(false);
            }
        });
    }

    private double computeThreshold(String symbol, BigDecimal currentPrice) {
        BigDecimal atr = lastAtr5m.get(symbol);
        if (atr != null && atr.compareTo(BigDecimal.ZERO) > 0 && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
            double atrPct = atr.doubleValue() / currentPrice.doubleValue();
            return atrPct * ATR_MULTIPLIER;
        }
        return FALLBACK_THRESHOLDS.getOrDefault(symbol, DEFAULT_FALLBACK);
    }

    private void executeVolatilityResponse(String symbol) {
        long startMs = System.currentTimeMillis();
        try {
            // 1. 先跑轻周期刷新信号（零LLM，~5-10s）
            if (lightCycleService.hasCacheFor(symbol)) {
                String fearGreedData = fetchFearGreed();
                lightCycleService.runLightRefresh(symbol, fearGreedData);
                log.info("[Sentinel] 轻周期完成 symbol={} 耗时{}ms", symbol, System.currentTimeMillis() - startMs);
            } else {
                log.info("[Sentinel] 无轻周期缓存，跳过信号刷新 symbol={}", symbol);
            }

            // 2. 触发AI交易决策（用刚刷新的信号）
            aiTradingScheduler.triggerTradingCycle(List.of(symbol));
            log.info("[Sentinel] AI交易已触发 symbol={} 总耗时{}ms", symbol, System.currentTimeMillis() - startMs);

        } catch (Exception e) {
            log.error("[Sentinel] 波动响应异常 symbol={}", symbol, e);
        }
    }

    private String fetchFearGreed() {
        try {
            String data = binanceRestClient.getFearGreedIndex(2);
            return (data != null && !data.isBlank()) ? data : "{}";
        } catch (Exception e) {
            return "{}";
        }
    }
}
