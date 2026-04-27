package com.mawai.wiibservice.agent.trading;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.service.FuturesTradingService;
import com.mawai.wiibservice.task.AiTradingScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 持仓回撤哨兵：每分钟扫 AI 账户的 OPEN 持仓，浮盈回撤时主动平仓（不再唤醒 cycle）。
 * <p>
 * 三条件OR触发，命中即 closePosition：
 * A. PnL% 下降 ≥ {@link #PNL_PCT_DROP_THRESHOLD_PPT} 个百分点（窗口快照）
 * B. 盈利金额回撤 ≥ {@link #PROFIT_DRAWDOWN_THRESHOLD_PCT}%（窗口最高 > MIN_BASE 才启用）
 * C. 峰值浮盈 ≥ {@link #PEAK_ATR_THRESHOLD}×ATR 且 当前浮盈 ≤ peak×{@link #PEAK_DRAWDOWN_RATIO}
 *    （基于生产 TradingExecutionState 的 peak 记忆，A/B 看 5min 窗口，C 看整个持仓生命周期）
 * <p>
 * 默认关闭；参数/开关通过 Admin 运行时切换，重启恢复默认。
 * 跨入口 30s 冷却由 {@link AiTradingScheduler#isRecentlyTriggered} 提供，防 cycle/sentinel 双重 close。
 */
@Slf4j
@Component
public class PositionDrawdownSentinel {

    // ==================== Admin 可配参数（volatile，重启恢复默认） ====================
    // 默认关闭：主动平仓容易抢在TP/SL前全平，观察期先让主盯盘逻辑接管。
    public static volatile boolean ENABLED = false;
    public static volatile int WINDOW_MINUTES = 5;
    public static volatile double PNL_PCT_DROP_THRESHOLD_PPT = 2.0;
    public static volatile double PROFIT_DRAWDOWN_THRESHOLD_PCT = 50.0;
    public static volatile double PROFIT_DRAWDOWN_MIN_BASE = 50.0;
    public static volatile int COOLDOWN_MINUTES = 5;

    // Peak-based 硬平仓门槛（独立于窗口快照，基于 Executor 的跨 cycle peak 记忆）
    // 峰值浮盈 ≥PEAK_ATR_THRESHOLD×ATR 且 当前浮盈 ≤ peak×PEAK_DRAWDOWN_RATIO → 立即平仓
    public static volatile double PEAK_ATR_THRESHOLD = 1.5;
    public static volatile double PEAK_DRAWDOWN_RATIO = 0.5;

    /** 避免与其他Sentinel/事件在30s内重复触发同一symbol的cycle（复用scheduler内的全局记录）。 */
    private static final long CROSS_SOURCE_GUARD_SECONDS = 30;

    private final AiTradingScheduler aiTradingScheduler;
    private final FuturesTradingService futuresTradingService;
    private final QuantForecastCycleMapper cycleMapper;
    private final TradingExecutionState tradingExecutionState;

    private final Map<Long, Deque<PnlSnapshot>> windows = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastTriggerMs = new ConcurrentHashMap<>();

    private record PnlSnapshot(long timestampMs, BigDecimal pnl, BigDecimal pnlPct) {}

    public PositionDrawdownSentinel(AiTradingScheduler aiTradingScheduler,
                                    FuturesTradingService futuresTradingService,
                                    QuantForecastCycleMapper cycleMapper,
                                    TradingExecutionState tradingExecutionState) {
        this.aiTradingScheduler = aiTradingScheduler;
        this.futuresTradingService = futuresTradingService;
        this.cycleMapper = cycleMapper;
        this.tradingExecutionState = tradingExecutionState;
    }

    @Scheduled(fixedDelay = 60_000L)
    public void scan() {
        if (!ENABLED) return;
        Long aiUserId = aiTradingScheduler.getAiUserId();
        if (aiUserId == null || aiUserId == 0L) return;

        List<FuturesPositionDTO> positions;
        try {
            positions = futuresTradingService.getUserPositions(aiUserId, null);
        } catch (Exception e) {
            log.warn("[DrawdownSentinel] 拉取持仓失败: {}", e.getMessage());
            return;
        }
        if (positions == null || positions.isEmpty()) {
            windows.clear();
            lastTriggerMs.clear();
            return;
        }

        long now = System.currentTimeMillis();
        long windowMs = Math.max(1, WINDOW_MINUTES) * 60_000L;
        long cutoff = now - windowMs;
        long cooldownMs = Math.max(1, COOLDOWN_MINUTES) * 60_000L;

        Set<Long> activeIds = new HashSet<>();

        for (FuturesPositionDTO p : positions) {
            if (!"OPEN".equals(p.getStatus())) continue;
            if (p.getId() == null || p.getUnrealizedPnl() == null || p.getUnrealizedPnlPct() == null) continue;
            activeIds.add(p.getId());

            Deque<PnlSnapshot> win = windows.computeIfAbsent(p.getId(), k -> new ConcurrentLinkedDeque<>());
            win.addLast(new PnlSnapshot(now, p.getUnrealizedPnl(), p.getUnrealizedPnlPct()));
            while (!win.isEmpty() && win.peekFirst().timestampMs() < cutoff) win.pollFirst();
            if (win.size() < 2) continue;

            // 同持仓冷却期内不重复触发
            Long lastMs = lastTriggerMs.get(p.getId());
            if (lastMs != null && now - lastMs < cooldownMs) continue;

            BigDecimal maxPnlPct = win.peekFirst().pnlPct();
            BigDecimal maxPnl = win.peekFirst().pnl();
            for (PnlSnapshot s : win) {
                if (s.pnlPct().compareTo(maxPnlPct) > 0) maxPnlPct = s.pnlPct();
                if (s.pnl().compareTo(maxPnl) > 0) maxPnl = s.pnl();
            }

            double pnlPctDropPpt = maxPnlPct.subtract(p.getUnrealizedPnlPct()).doubleValue();
            boolean conditionA = pnlPctDropPpt >= PNL_PCT_DROP_THRESHOLD_PPT;

            double profitDrawdownPct = 0;
            boolean conditionB = false;
            if (maxPnl.doubleValue() > PROFIT_DRAWDOWN_MIN_BASE) {
                double diff = maxPnl.doubleValue() - p.getUnrealizedPnl().doubleValue();
                if (diff > 0) {
                    profitDrawdownPct = diff / maxPnl.doubleValue() * 100.0;
                    conditionB = profitDrawdownPct >= PROFIT_DRAWDOWN_THRESHOLD_PCT;
                }
            }

            // 条件 C：Peak-based ATR 硬平仓
            // A/B 基于 sentinel 窗口快照；C 基于生产执行状态里的生命周期峰值
            // 峰值浮盈 ≥PEAK_ATR_THRESHOLD×ATR 且 当前浮盈 ≤ peak×RATIO → 立即平
            boolean conditionC = false;
            double peakAtrMul = 0;
            double peakDrawdownPct = 0;
            BigDecimal peakProfit = tradingExecutionState.getPositionMaxProfit(p.getId());
            BigDecimal atr = getAtrBySymbol(p.getSymbol());
            if (peakProfit != null && atr != null && atr.signum() > 0
                    && p.getQuantity() != null && p.getQuantity().signum() > 0
                    && p.getUnrealizedPnl() != null) {
                peakAtrMul = peakProfit.doubleValue() / atr.doubleValue();
                if (peakAtrMul >= PEAK_ATR_THRESHOLD) {
                    // peakProfit 是单价差（价格维度），当前浮盈(总 PnL)换算到单价差对齐
                    BigDecimal currentProfitPrice = p.getUnrealizedPnl()
                            .divide(p.getQuantity(), 8, RoundingMode.HALF_DOWN);
                    BigDecimal threshold = peakProfit.multiply(BigDecimal.valueOf(PEAK_DRAWDOWN_RATIO));
                    conditionC = currentProfitPrice.compareTo(threshold) <= 0;
                    if (peakProfit.signum() > 0) {
                        peakDrawdownPct = (peakProfit.doubleValue() - currentProfitPrice.doubleValue())
                                / peakProfit.doubleValue() * 100.0;
                    }
                }
            }

            if (!conditionA && !conditionB && !conditionC) continue;

            // 跨入口冷却：其他入口刚触发过 cycle → 让 managePositions 先处理，避免双重 close
            if (aiTradingScheduler.isRecentlyTriggered(p.getSymbol(), CROSS_SOURCE_GUARD_SECONDS)) {
                log.info("[DrawdownSentinel] {} 触发条件达标但30s内已有其他入口触发cycle→跳过 positionId={}",
                        p.getSymbol(), p.getId());
                lastTriggerMs.put(p.getId(), now);
                continue;
            }

            List<String> parts = new ArrayList<>();
            if (conditionA) parts.add(String.format("PnL%%下降%.2fppt≥%.1f",
                    pnlPctDropPpt, PNL_PCT_DROP_THRESHOLD_PPT));
            if (conditionB) parts.add(String.format("窗口盈利回撤%.1f%%≥%.1f%%(基线%.1f→%.1f)",
                    profitDrawdownPct, PROFIT_DRAWDOWN_THRESHOLD_PCT,
                    maxPnl.doubleValue(), p.getUnrealizedPnl().doubleValue()));
            if (conditionC) parts.add(String.format("Peak%.2fATR回撤%.1f%%≥%.0f%%",
                    peakAtrMul, peakDrawdownPct, PEAK_DRAWDOWN_RATIO * 100));
            String triggerReason = String.join(" + ", parts);

            log.info("[DrawdownSentinel] ⚠ 主动平仓 symbol={} positionId={} side={} 当前PnL={}({}%) 窗口最高PnL={}({}%) 原因=[{}]",
                    p.getSymbol(), p.getId(), p.getSide(),
                    p.getUnrealizedPnl().toPlainString(), p.getUnrealizedPnlPct().toPlainString(),
                    maxPnl.toPlainString(), maxPnlPct.toPlainString(), triggerReason);

            lastTriggerMs.put(p.getId(), now);
            try {
                FuturesCloseRequest req = new FuturesCloseRequest();
                req.setPositionId(p.getId());
                req.setQuantity(p.getQuantity());
                req.setOrderType("MARKET");
                var resp = futuresTradingService.closePosition(aiUserId, req);
                log.info("[DrawdownSentinel] ✓ 平仓成功 symbol={} posId={} pnl={}",
                        p.getSymbol(), p.getId(), resp != null ? resp.getRealizedPnl() : "n/a");
            } catch (Exception e) {
                log.warn("[DrawdownSentinel] 平仓失败 symbol={} posId={}: {}",
                        p.getSymbol(), p.getId(), e.getMessage());
            }
        }

        // 清理已平仓持仓的状态
        windows.keySet().removeIf(id -> !activeIds.contains(id));
        lastTriggerMs.keySet().removeIf(id -> !activeIds.contains(id));
    }

    /** 从最新 QuantForecastCycle 的 snapshotJson 中读取 5m ATR。异常/缺失返回 null，调用方按 null 跳过。 */
    private BigDecimal getAtrBySymbol(String symbol) {
        try {
            QuantForecastCycle cycle = cycleMapper.selectLatest(symbol);
            if (cycle == null || cycle.getSnapshotJson() == null) return null;
            JSONObject snap = JSON.parseObject(cycle.getSnapshotJson());
            return snap.getBigDecimal("atr5m");
        } catch (Exception e) {
            log.debug("[DrawdownSentinel] 读 ATR 失败 symbol={}: {}", symbol, e.getMessage());
            return null;
        }
    }
}
