package com.mawai.wiibquant.agent.strategy.core;

import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.strategy.execution.StrategyExecutionPort;
import com.mawai.wiibcommon.market.ForceOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    private final StrategyExecutionPort executionService;   // testnet/sim 按配置路由（ExecutionRoutingConfig）
    private final ConcurrentMap<String, WindowedMarketView> views = new ConcurrentHashMap<>();
    // 信号落库腿去重：STOP/LIMIT 信号每5m reaffirm 一次，同腿只记首条，换腿/换价/断档后才再落库
    private final ConcurrentMap<String, String> lastRecordedLeg = new ConcurrentHashMap<>();

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
        if (!enabled || strategies.isEmpty()) return;
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
                    // 持仓钩子先于新信号评估（镜像回测顺序）：时间出场先平旧仓，同bar新信号才不会被旧仓挡下
                    executionService.onPositionBarClosed(symbol, strategy, view);
                    Optional<StrategySignal> signal = strategy.onBarClosed(symbol, view);
                    if (signal.isPresent()) {
                        StrategySignal s = signal.get();
                        String legFingerprint = s.orderType() + ":" + s.side() + ":"
                                + s.entryRefPrice().stripTrailingZeros().toPlainString();
                        String legKey = strategy.id() + ":" + symbol;
                        if (!legFingerprint.equals(lastRecordedLeg.put(legKey, legFingerprint))) {
                            recorder.record(s, legTags(symbol, s.isLong()));
                        }
                        executionService.onSignal(symbol, s, strategy);              // 实盘执行(默认关)
                    } else {
                        lastRecordedLeg.remove(strategy.id() + ":" + symbol);
                        executionService.noSignal(symbol, strategy.id());            // 腿失效，撤该策略挂单
                    }
                    executionService.tick(symbol, view.nowMs());                     // 成交/超时/平仓轮询
                }
            }
        } catch (Exception e) {
            log.warn("[StrategyRuntime] evaluate失败 symbol={} msg={}", symbol, e.getMessage());
        }
    }

    /**
     * 监控页信号快照：enabled 策略 × 各自币篮全量。view 惰性 seed 与 evaluate 同源；
     * 快照在 view 锁内执行，避开 5m 收盘并发写窗口。
     */
    public List<StrategySignalState> signalStates() {
        if (!enabled) return List.of();
        Set<String> ids = enabledIdSet();
        List<StrategySignalState> out = new ArrayList<>();
        for (TradingStrategySpi strategy : strategies) {
            if (!ids.contains(strategy.id().toUpperCase(Locale.ROOT))) continue;
            for (String symbol : strategy.symbols()) {
                try {
                    WindowedMarketView view = views.computeIfAbsent(symbol, this::seedView);
                    synchronized (view) {
                        StrategySignalState state = strategy.signalState(symbol, view);
                        if (state != null) out.add(state);
                    }
                } catch (Exception e) {
                    log.warn("[StrategyRuntime] signalState失败 strategy={} symbol={} msg={}",
                            strategy.id(), symbol, e.getMessage());
                }
            }
        }
        return out;
    }

    private WindowedMarketView seedView(String symbol) {
        // 策略篮子币(SOL/DOGE/XRP)不在 WATCH_SYMBOLS，watchdog 不回填它们——seed 前自补 SEED_BARS
        // 窗口，否则冷启动空视图，SQZMOM(4H) 要实时攒几天才有信号且重启清零。insertIgnore 幂等，
        // 已有数据的 symbol 重复补无害（每进程每 symbol 仅首次触达时执行一次）。
        long now = System.currentTimeMillis();
        try {
            klineHistoryStore.backfill(symbol, now - (long) SEED_BARS * KlineHistoryStore.DEFAULT_BAR_MILLIS, now);
        } catch (Exception e) {
            log.warn("[StrategyRuntime] seed回填失败(用既有数据继续) symbol={} msg={}", symbol, e.getMessage());
        }
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
