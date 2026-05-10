package com.mawai.wiibservice.agent.trading;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易执行跨 cycle 记忆。
 * 生产环境使用 Spring 单例；回测每次 run 自己 new，避免状态串场。
 */
@Component
public class TradingExecutionState {

    private final ConcurrentHashMap<String, Long> lastEntryMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> reversalStreak = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PositionPeak> positionPeaks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ExitPlan> exitPlans = new ConcurrentHashMap<>();

    // 回测用：覆盖当前时间（毫秒），为 null 时回退到 System.currentTimeMillis()
    @Getter
    @Setter
    private volatile Long mockNowMs;

    public record PositionPeak(BigDecimal maxProfit, boolean partialTpDone) {
        PositionPeak withMaxProfit(BigDecimal p) {
            return new PositionPeak(p, partialTpDone);
        }

        PositionPeak withPartialTpDone() {
            return new PositionPeak(maxProfit, true);
        }
    }

    public Long getLastEntryMs(String symbol) {
        return lastEntryMs.get(symbol);
    }

    public void markEntry(String symbol, long epochMs) {
        lastEntryMs.put(symbol, epochMs);
    }

    public void cleanupPositionMemory(Collection<Long> livePositionIds) {
        positionPeaks.keySet().removeIf(id -> !livePositionIds.contains(id));
        reversalStreak.keySet().removeIf(id -> !livePositionIds.contains(id));
        exitPlans.keySet().removeIf(id -> !livePositionIds.contains(id));
    }

    public void clearPosition(Long positionId) {
        positionPeaks.remove(positionId);
        reversalStreak.remove(positionId);
        exitPlans.remove(positionId);
    }

    public ExitPlan getExitPlan(Long positionId) {
        return exitPlans.get(positionId);
    }

    public void putExitPlan(Long positionId, ExitPlan plan) {
        if (positionId == null || plan == null) {
            return;
        }
        exitPlans.put(positionId, plan);
    }

    public ExitPlan removeExitPlan(Long positionId) {
        return exitPlans.remove(positionId);
    }

    public void recordPositiveProfit(Long positionId, BigDecimal profit) {
        positionPeaks.compute(positionId, (k, old) -> {
            if (old == null) return new PositionPeak(profit, false);
            return profit.compareTo(old.maxProfit()) > 0 ? old.withMaxProfit(profit) : old;
        });
    }

    public PositionPeak getPositionPeak(Long positionId) {
        return positionPeaks.get(positionId);
    }

    public BigDecimal getPositionMaxProfit(Long positionId) {
        PositionPeak peak = positionPeaks.get(positionId);
        return peak == null ? null : peak.maxProfit();
    }

    public void markPartialTpDone(Long positionId) {
        positionPeaks.computeIfPresent(positionId, (k, v) -> v.withPartialTpDone());
    }

    public int incrementReversalStreak(Long positionId) {
        return reversalStreak.merge(positionId, 1, Integer::sum);
    }

    public void clearReversalStreak(Long positionId) {
        reversalStreak.remove(positionId);
    }
}
