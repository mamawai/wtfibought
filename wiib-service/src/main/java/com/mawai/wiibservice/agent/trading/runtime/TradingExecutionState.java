package com.mawai.wiibservice.agent.trading.runtime;

import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易执行跨 cycle 记忆。
 * 生产环境使用 Spring 单例；回测每次 run 自己 new，避免状态串场。
 */
@Component
public class TradingExecutionState {

    private final ConcurrentHashMap<String, Long> lastEntryMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> reversalStreak = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> reversalStreakSignalKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> fastFailStreak = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> fastFailStreakSignalKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> maSlopeFastFailStreak = new ConcurrentHashMap<>();
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
        reversalStreakSignalKey.keySet().removeIf(id -> !livePositionIds.contains(id));
        fastFailStreak.keySet().removeIf(id -> !livePositionIds.contains(id));
        fastFailStreakSignalKey.keySet().removeIf(id -> !livePositionIds.contains(id));
        exitPlans.keySet().removeIf(id -> !livePositionIds.contains(id));
    }

    public void clearPosition(Long positionId) {
        positionPeaks.remove(positionId);
        reversalStreak.remove(positionId);
        reversalStreakSignalKey.remove(positionId);
        fastFailStreak.remove(positionId);
        fastFailStreakSignalKey.remove(positionId);
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

    public void incrementReversalStreak(Long positionId) {
        reversalStreak.merge(positionId, 1, Integer::sum);
    }

    public int incrementAndGetReversalStreak(Long positionId) {
        if (positionId == null) {
            return 0;
        }
        return reversalStreak.merge(positionId, 1, Integer::sum);
    }

    public int recordReversalStreakOnSignal(Long positionId, String signalKey) {
        if (positionId == null) {
            return 0;
        }
        if (signalKey == null || signalKey.isBlank()) {
            return incrementAndGetReversalStreak(positionId);
        }
        String normalizedKey = signalKey.trim();
        Integer current = reversalStreak.get(positionId);
        String previousKey = reversalStreakSignalKey.get(positionId);
        if (normalizedKey.equals(previousKey)) {
            if (current != null) {
                return current;
            }
            reversalStreak.put(positionId, 1);
            return 1;
        }
        reversalStreakSignalKey.put(positionId, normalizedKey);
        return reversalStreak.merge(positionId, 1, Integer::sum);
    }

    public int getReversalStreak(Long positionId) {
        if (positionId == null) {
            return 0;
        }
        return reversalStreak.getOrDefault(positionId, 0);
    }

    public void clearReversalStreak(Long positionId) {
        reversalStreak.remove(positionId);
        reversalStreakSignalKey.remove(positionId);
    }

    public int recordFastFailStreakOnSignal(Long positionId, String signalKey) {
        if (positionId == null) {
            return 0;
        }
        if (signalKey == null || signalKey.isBlank()) {
            return fastFailStreak.merge(positionId, 1, Integer::sum);
        }
        String normalizedKey = signalKey.trim();
        Integer current = fastFailStreak.get(positionId);
        String previousKey = fastFailStreakSignalKey.get(positionId);
        if (normalizedKey.equals(previousKey)) {
            if (current != null) {
                return current;
            }
            fastFailStreak.put(positionId, 1);
            return 1;
        }
        fastFailStreakSignalKey.put(positionId, normalizedKey);
        return fastFailStreak.merge(positionId, 1, Integer::sum);
    }

    public int getFastFailStreak(Long positionId) {
        if (positionId == null) {
            return 0;
        }
        return fastFailStreak.getOrDefault(positionId, 0);
    }

    public void clearFastFailStreak(Long positionId) {
        fastFailStreak.remove(positionId);
        fastFailStreakSignalKey.remove(positionId);
    }

    public int recordMaSlopeFastFail(String symbol, String side) {
        String key = maSlopeWaveKey(symbol, side);
        if (key == null) {
            return 0;
        }
        return maSlopeFastFailStreak.merge(key, 1, Integer::sum);
    }

    public int getMaSlopeFastFailStreak(String symbol, String side) {
        String key = maSlopeWaveKey(symbol, side);
        if (key == null) {
            return 0;
        }
        return maSlopeFastFailStreak.getOrDefault(key, 0);
    }

    public void clearMaSlopeFastFailStreak(String symbol, String side) {
        String key = maSlopeWaveKey(symbol, side);
        if (key != null) {
            maSlopeFastFailStreak.remove(key);
        }
    }

    private String maSlopeWaveKey(String symbol, String side) {
        if (symbol == null || symbol.isBlank() || side == null || side.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase(Locale.ROOT) + "|" + side.trim().toUpperCase(Locale.ROOT);
    }
}
