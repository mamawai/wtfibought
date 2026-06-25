package com.mawai.wiibservice.agent.strategy.core;

import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibservice.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import com.mawai.wiibservice.agent.strategy.execution.TestnetExecutionService;
import com.mawai.wiibservice.service.ForceOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 插拔式策略实盘信号运行时。
 *
 * <p>5m KlineClosedEvent 驱动，策略读取实时流并产出 LIVE 信号。当前层只负责信号落库，
 * 下单执行由后续 StrategyExecutionAdapter 接入，避免把信号记录和交易副作用混在一起。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyRuntime {

    private static final int SEED_BARS = 12_000;

    private final List<TradingStrategySpi> strategies;
    private final KlineHistoryStore klineHistoryStore;
    private final StrategySignalRecorder recorder;
    private final ForceOrderService forceOrderService;
    private final TestnetExecutionService executionService;
    private final ConcurrentMap<String, WindowedMarketView> views = new ConcurrentHashMap<>();

    @Value("${strategy.runtime.enabled:false}")
    private boolean enabled;

    @Value("${strategy.runtime.enabled-ids:FIBO}")
    private String enabledIds;

    @Value("${strategy.runtime.liquidation-window-minutes:30}")
    private int liquidationWindowMinutes;

    @Value("${strategy.runtime.liquidation-threshold-usdt:500000}")
    private BigDecimal liquidationThreshold;

    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!enabled || event == null || strategies.isEmpty()) return;
        if (!KlineHistoryStore.DEFAULT_INTERVAL.equalsIgnoreCase(event.interval())) return;

        String symbol = normalizeSymbol(event.symbol());
        if (enabledStrategiesFor(symbol).isEmpty()) return;
        Thread.startVirtualThread(() -> evaluate(symbol, event.bar()));
    }

    private void evaluate(String symbol, KlineBar eventBar) {
        try {
            WindowedMarketView view = views.computeIfAbsent(symbol, this::seedView);
            synchronized (view) {
                // 事件已携带收盘 bar（KlineStreamConsumer republish 时重建），不再回查进程内缓存
                if (eventBar != null) {
                    view.append(eventBar);
                }
                for (TradingStrategySpi strategy : enabledStrategiesFor(symbol)) {
                    Optional<StrategySignal> signal = strategy.onBarClosed(symbol, view);
                    if (signal.isPresent()) {
                        recorder.record(signal.get(), legTags(symbol, signal.get().isLong()));
                        executionService.onSignal(symbol, signal.get(), strategy);   // 实盘执行(默认关)
                    } else {
                        executionService.noSignal(symbol);                           // 腿失效，撤挂单
                    }
                    executionService.tick(symbol, view.nowMs());                     // 成交/超时/平仓轮询
                }
            }
        } catch (Exception e) {
            log.warn("[StrategyRuntime] evaluate失败 symbol={} msg={}", symbol, e.getMessage());
        }
    }

    private WindowedMarketView seedView(String symbol) {
        Long latestClose = klineHistoryStore.latestCloseTime(symbol, KlineHistoryStore.DEFAULT_INTERVAL);
        long toMs = latestClose != null ? latestClose : System.currentTimeMillis();
        long fromMs = toMs - (long) SEED_BARS * KlineHistoryStore.DEFAULT_BAR_MILLIS;
        WindowedMarketView view = new WindowedMarketView(SEED_BARS + 2_000);
        klineHistoryStore.load(symbol, KlineHistoryStore.DEFAULT_INTERVAL, fromMs, toMs).forEach(view::append);
        int loaded = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, Integer.MAX_VALUE).size();
        view.baseGapDescription().ifPresent(gap ->
                log.warn("[StrategyRuntime] seed存在5m断档 symbol={} {}", symbol, gap));
        log.info("[StrategyRuntime] seed symbol={} bars={} toMs={}", symbol, loaded, toMs);
        return view;
    }

    private List<TradingStrategySpi> enabledStrategiesFor(String symbol) {
        Set<String> ids = enabledIdSet();
        return strategies.stream()
                .filter(strategy -> ids.contains(strategy.id().toUpperCase(Locale.ROOT)))
                .filter(strategy -> strategy.symbols().contains(symbol))
                .toList();
    }

    private Set<String> enabledIdSet() {
        if (enabledIds == null || enabledIds.isBlank()) return Set.of();
        return Arrays.stream(enabledIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private String legTags(String symbol, boolean signalIsLong) {
        try {
            List<ForceOrder> recent = forceOrderService.getRecent(symbol, Math.max(1, liquidationWindowMinutes));
            return LiquidationCascadeLeg.evaluate(recent, signalIsLong, liquidationThreshold);
        } catch (Exception e) {
            log.debug("[StrategyRuntime] 爆仓腿读取失败 symbol={} msg={}", symbol, e.getMessage());
            return "liq_cascade=ERROR";
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
