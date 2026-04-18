package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibservice.service.FuturesTradingService;
import com.mawai.wiibservice.task.AiTradingScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 持仓回撤哨兵：每分钟扫描AI账户的OPEN持仓，窗口内PnL急速恶化时唤醒AI Trader重新决策。
 * <p>
 * 双条件OR触发：
 * A. PnL% 下降 ≥ {@link #PNL_PCT_DROP_THRESHOLD_PPT} 个百分点
 * B. 盈利金额回撤 ≥ {@link #PROFIT_DRAWDOWN_THRESHOLD_PCT}（仅当窗口内最高盈利 > MIN_BASE 才启用）
 * <p>
 * 默认关闭；参数/开关通过 Admin 页面运行时切换，重启恢复默认。
 * 与 PriceVolatilitySentinel 并行工作：一个看市场波动、一个看持仓回撤，下游共享
 * AiTradingScheduler.runningSymbols 防并发；跨入口30s冷却由 isRecentlyTriggered 提供。
 */
@Slf4j
@Component
public class PositionDrawdownSentinel {

    // ==================== Admin 可配参数（volatile，重启恢复默认） ====================
    public static volatile boolean ENABLED = false;
    public static volatile int WINDOW_MINUTES = 5;
    public static volatile double PNL_PCT_DROP_THRESHOLD_PPT = 2.0;
    public static volatile double PROFIT_DRAWDOWN_THRESHOLD_PCT = 50.0;
    public static volatile double PROFIT_DRAWDOWN_MIN_BASE = 50.0;
    public static volatile int COOLDOWN_MINUTES = 5;

    /** 避免与其他Sentinel/事件在30s内重复触发同一symbol的cycle（复用scheduler内的全局记录）。 */
    private static final long CROSS_SOURCE_GUARD_SECONDS = 30;

    private final AiTradingScheduler aiTradingScheduler;
    private final FuturesTradingService futuresTradingService;

    private final Map<Long, Deque<PnlSnapshot>> windows = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastTriggerMs = new ConcurrentHashMap<>();

    private record PnlSnapshot(long timestampMs, BigDecimal pnl, BigDecimal pnlPct) {}

    public PositionDrawdownSentinel(AiTradingScheduler aiTradingScheduler,
                                    FuturesTradingService futuresTradingService) {
        this.aiTradingScheduler = aiTradingScheduler;
        this.futuresTradingService = futuresTradingService;
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
            if (!conditionA && !conditionB) continue;

            // 跨入口冷却：其他Sentinel/事件刚触发过 → 跳过，避免浪费cycleNo
            if (aiTradingScheduler.isRecentlyTriggered(p.getSymbol(), CROSS_SOURCE_GUARD_SECONDS)) {
                log.info("[DrawdownSentinel] {} 触发条件达标但30s内已有其他入口触发→跳过 positionId={}",
                        p.getSymbol(), p.getId());
                lastTriggerMs.put(p.getId(), now);
                continue;
            }

            String triggerReason;
            if (conditionA && conditionB) {
                triggerReason = String.format("PnL%%下降%.2fppt(≥%.1f) + 盈利回撤%.1f%%(≥%.1f%%)",
                        pnlPctDropPpt, PNL_PCT_DROP_THRESHOLD_PPT,
                        profitDrawdownPct, PROFIT_DRAWDOWN_THRESHOLD_PCT);
            } else if (conditionA) {
                triggerReason = String.format("PnL%%下降%.2fppt≥%.1fppt触发",
                        pnlPctDropPpt, PNL_PCT_DROP_THRESHOLD_PPT);
            } else {
                triggerReason = String.format("盈利回撤%.1f%%≥%.1f%%触发(基线%.1f→当前%.1f)",
                        profitDrawdownPct, PROFIT_DRAWDOWN_THRESHOLD_PCT,
                        maxPnl.doubleValue(), p.getUnrealizedPnl().doubleValue());
            }

            log.info("[DrawdownSentinel] ⚠ 触发 symbol={} positionId={} side={} 当前PnL={}({}%) 窗口最高PnL={}({}%) 窗口={}min 原因=[{}]",
                    p.getSymbol(), p.getId(), p.getSide(),
                    p.getUnrealizedPnl().toPlainString(), p.getUnrealizedPnlPct().toPlainString(),
                    maxPnl.toPlainString(), maxPnlPct.toPlainString(),
                    WINDOW_MINUTES, triggerReason);

            lastTriggerMs.put(p.getId(), now);
            try {
                int cycleNo = aiTradingScheduler.triggerTradingCycle(List.of(p.getSymbol()));
                log.info("[DrawdownSentinel] AI Trader已唤醒 symbol={} cycleNo={}", p.getSymbol(), cycleNo);
            } catch (Exception e) {
                log.warn("[DrawdownSentinel] triggerTradingCycle失败 symbol={}: {}", p.getSymbol(), e.getMessage());
            }
        }

        // 清理已平仓持仓的状态
        windows.keySet().removeIf(id -> !activeIds.contains(id));
        lastTriggerMs.keySet().removeIf(id -> !activeIds.contains(id));
    }
}
