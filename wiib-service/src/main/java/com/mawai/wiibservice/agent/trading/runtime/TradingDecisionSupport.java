package com.mawai.wiibservice.agent.trading.runtime;

import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlanRecovery;
import com.mawai.wiibservice.agent.trading.ops.TradingOperations;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.QuantSignalDecision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TradingDecisionSupport {

    public static final String PATH_BREAKOUT = "BREAKOUT";
    public static final String PATH_MR = "MR";
    public static final String PATH_LEGACY_TREND = "LEGACY_TREND";
    public static final String PATH_MA_SLOPE = "MA_SLOPE";
    private static final String LEGACY_PATH_TREND = "TREND";
    private static final String LEGACY_PATH_MR = "MEAN_REVERSION";
    private static final long ALLOWED_CLOCK_SKEW_MINUTES = 5;
    private static final double STRONG_BACKGROUND_CONFIDENCE = 0.68;

    private TradingDecisionSupport() {
    }

    public static DeterministicTradingExecutor.ExecutionResult hold(String reason) {
        return new DeterministicTradingExecutor.ExecutionResult("HOLD", reason, "");
    }

    public static long currentTimeMillis(TradingExecutionState state) {
        Long mockNowMs = state != null ? state.getMockNowMs() : null;
        return mockNowMs != null ? mockNowMs : System.currentTimeMillis();
    }

    public static LocalDateTime currentDateTime(TradingExecutionState state) {
        return currentDateTime(currentTimeMillis(state));
    }

    public static LocalDateTime currentDateTime(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    public record ExitEvaluationContext(List<FuturesPositionDTO> positions,
                                        TradingExecutionState state,
                                        LocalDateTime now) {
    }

    /**
     * 退出引擎共用准备：取当前时间、清理已消失仓位的旧记忆、补齐缺失的 ExitPlan。
     *
     * <p>生产入口已传全账户 OPEN 仓位 ID，外层会全局清理；旧入口/回测只知道当前 symbol，
     * 这里才按当前 positions 局部清理，避免误删其它 symbol 的持仓记忆。</p>
     */
    public static ExitEvaluationContext prepareExitEvaluation(TradingDecisionContext decision, boolean cleanupFromCurrentPositions) {
        TradingExecutionState state = decision.state();
        List<FuturesPositionDTO> positions = decision.symbolPositions() != null ? decision.symbolPositions() : List.of();
        LocalDateTime now = currentDateTime(state);

        if (cleanupFromCurrentPositions) {
            List<Long> livePositionIds = positions.stream()
                    .map(FuturesPositionDTO::getId)
                    .filter(Objects::nonNull)
                    .toList();
            state.cleanupPositionMemory(livePositionIds);
        }
        ExitPlanRecovery.ensureRecoveredPlans(decision, now);
        return new ExitEvaluationContext(positions, state, now);
    }

    /**
     * 开仓主信号：H6 作为主要执行方向，H12/H24 用于背景确认和仓位缩放。
     * <p>
     * 逻辑：
     * <ul>
     *   <li>H6 是主执行方向——驱动开仓/平仓决策</li>
     *   <li>H6 与 H12/H24 同向 → 正常仓位</li>
     *   <li>H6 与 H12 反向 → 降仓位，仍允许入场</li>
     *   <li>H6 与 H24 反向 → 大幅降仓位，接近禁止新开仓</li>
     *   <li>H6 无方向但 H12+H24 同向强信号 → 允许保守入场</li>
     *   <li>H24 不单独触发入场</li>
     * </ul>
     */
    public static QuantSignalDecision findBestSignalWithPriority(List<QuantSignalDecision> signals,
                                                          LocalDateTime forecastTime,
                                                          LocalDateTime now) {
        return findSignalWithPriority(signals, forecastTime, now, false);
    }

    /**
     * 开仓参考信号：NO_TRADE 不硬禁开仓，只缩放仓位。
     */
    public static QuantSignalDecision findReferenceSignalWithPriority(List<QuantSignalDecision> signals,
                                                                      LocalDateTime forecastTime,
                                                                      LocalDateTime now) {
        return findSignalWithPriority(signals, forecastTime, now, true);
    }

    private static QuantSignalDecision findSignalWithPriority(List<QuantSignalDecision> signals,
                                                              LocalDateTime forecastTime,
                                                              LocalDateTime now,
                                                              boolean allowNoTradeDirection) {
        if (signals == null || signals.isEmpty()) return null;

        long ageMinutes = forecastAgeMinutes(forecastTime, now);
        if (ageMinutes < -ALLOWED_CLOCK_SKEW_MINUTES) return null;

        QuantSignalDecision sigH6 = null, sigH12 = null, sigH24 = null;
        for (QuantSignalDecision s : signals) {
            if (s.getHorizon() == null) continue;
            switch (s.getHorizon()) {
                case "H6" -> sigH6 = horizonFresh(s, ageMinutes) ? s : null;
                case "H12" -> sigH12 = horizonFresh(s, ageMinutes) ? s : null;
                case "H24" -> sigH24 = horizonFresh(s, ageMinutes) ? s : null;
                // 旧 0_10/10_20/20_30 属已下线短线系统，交易入口不再消费
                default -> {}
            }
        }

        // H6 为主执行方向
        QuantSignalDecision primary = copyIfValid(sigH6, allowNoTradeDirection, false);
        boolean backgroundFallback = false;

        // H6 无方向但 H12+H24 同向强信号 → 保守入场（仓位降低）
        if (primary == null && sigH12 != null && sigH24 != null) {
            QuantSignalDecision validH12 = copyIfValid(sigH12, false, true);
            QuantSignalDecision validH24 = copyIfValid(sigH24, false, true);
            if (validH12 != null && validH24 != null
                    && validH12.getDirection() != null
                    && validH12.getDirection().equals(validH24.getDirection())) {
                primary = validH12;
                backgroundFallback = true;
            }
        }

        if (primary == null) return null;

        // 背景确认：H12/H24 方向一致性影响仓位缩放
        double positionMultiplier = backgroundFallback ? 0.50 : 1.0;
        if (primary.getDirection() != null && !"NO_TRADE".equals(primary.getDirection())) {
            String h6Dir = primary.getDirection();
            String h12Dir = sigH12 != null ? sigH12.getDirection() : null;
            String h24Dir = sigH24 != null ? sigH24.getDirection() : null;

            // H6 vs H12 反向 → 降仓位
            if (!backgroundFallback && h6Dir != null && h12Dir != null && !h6Dir.equals(h12Dir)
                    && !"NO_TRADE".equals(h12Dir)) {
                positionMultiplier *= 0.65;
            }
            // H6 vs H24 反向 → 大幅降仓位
            if (!backgroundFallback && h6Dir != null && h24Dir != null && !h6Dir.equals(h24Dir)
                    && !"NO_TRADE".equals(h24Dir)) {
                positionMultiplier *= 0.55;
            }
            // H12+H24 都同向确认 → 强化
            if (!backgroundFallback && h12Dir != null && h24Dir != null && h12Dir.equals(h6Dir) && h24Dir.equals(h6Dir)) {
                double boosted = Math.min(1.0, primary.getConfidence().doubleValue() * 1.10);
                primary = copySignalWithConfidence(primary, BigDecimal.valueOf(boosted));
            }
        }

        // 仓位缩放注入到 maxPositionPct
        if (Math.abs(positionMultiplier - 1.0) > 0.01 && primary.getMaxPositionPct() != null) {
            double adjustedPos = primary.getMaxPositionPct().doubleValue() * positionMultiplier;
            primary.setMaxPositionPct(BigDecimal.valueOf(Math.clamp(adjustedPos, 0.0, 1.0)));
        }

        return primary;
    }

    /** 持仓管理：按 horizon 自身有效期取仍可用的最高置信度方向信号。 */
    public static QuantSignalDecision findBestSignal(List<QuantSignalDecision> signals,
                                              LocalDateTime forecastTime,
                                              LocalDateTime now) {
        if (signals == null || signals.isEmpty()) return null;
        long ageMinutes = forecastAgeMinutes(forecastTime, now);
        if (ageMinutes < -ALLOWED_CLOCK_SKEW_MINUTES) return null;
        QuantSignalDecision best = null;
        for (QuantSignalDecision s : signals) {
            if (s.getHorizon() == null) continue;
            if (s.getDirection() == null || "NO_TRADE".equals(s.getDirection())) continue;
            if (s.getConfidence() == null) continue;
            if (!horizonFresh(s, ageMinutes)) continue;
            if ("H24".equals(s.getHorizon()) && !strongDirectional(s)) continue;
            if (best == null || s.getConfidence().compareTo(best.getConfidence()) > 0) {
                best = s;
            }
        }
        return best;
    }

    public static BigDecimal getCurrentStopLossPrice(FuturesPositionDTO pos) {
        if (pos.getStopLosses() == null || pos.getStopLosses().isEmpty()) return null;
        return pos.getStopLosses().getFirst().getPrice();
    }

    public static BigDecimal getCurrentTakeProfitPrice(FuturesPositionDTO pos) {
        if (pos.getTakeProfits() == null || pos.getTakeProfits().isEmpty()) return null;
        return pos.getTakeProfits().getFirst().getPrice();
    }

    public static BigDecimal calcCurrentTargetProgress(
            FuturesPositionDTO pos, BigDecimal profit, MarketContext ctx, SymbolProfile profile) {
        BigDecimal targetDistance = calcTargetDistance(pos, ctx, profile);
        if (targetDistance.signum() <= 0 || profit.signum() <= 0) {
            return null;
        }
        return profit.divide(targetDistance, 4, RoundingMode.HALF_UP);
    }

    public static BigDecimal calcTargetDistance(FuturesPositionDTO pos, MarketContext ctx, SymbolProfile profile) {
        BigDecimal entry = pos.getEntryPrice();
        BigDecimal tp = getCurrentTakeProfitPrice(pos);
        BigDecimal targetDistance = tp != null && entry != null ? tp.subtract(entry).abs() : null;
        if (targetDistance == null || targetDistance.signum() <= 0) {
            double tpAtr = switch (normalizeStrategyPath(pos.getMemo())) {
                case PATH_BREAKOUT -> profile.breakoutTpAtr();
                case PATH_MR -> profile.revertTpMaxAtr();
                default -> profile.trendTpAtr();
            };
            targetDistance = ctx.atr.multiply(BigDecimal.valueOf(tpAtr));
        }
        return targetDistance;
    }

    public static String syncRemainingRiskOrders(
            TradingOperations tools, Long positionId, BigDecimal currentSl, BigDecimal currentTp, BigDecimal remainingQty) {
        StringBuilder log = new StringBuilder();
        if (currentSl != null && currentSl.signum() > 0) {
            log.append("同步剩余止损: ")
                    .append(tools.setStopLoss(positionId, currentSl, remainingQty))
                    .append("\n");
        }
        if (currentTp != null && currentTp.signum() > 0) {
            log.append("同步剩余止盈: ")
                    .append(tools.setTakeProfit(positionId, currentTp, remainingQty))
                    .append("\n");
        }
        return log.toString();
    }

    public static boolean isTradeSuccess(String result) {
        return result != null && (result.startsWith("开仓成功") || result.startsWith("平仓成功"));
    }

    public static boolean isUpdateSuccess(String result) {
        return result != null && result.contains("成功") && !result.contains("失败");
    }

    public static String normalizeStrategyPath(String memo) {
        if (memo == null) return "";
        if (LEGACY_PATH_MR.equals(memo)) return PATH_MR;
        if (LEGACY_PATH_TREND.equals(memo)) return PATH_LEGACY_TREND;
        return memo;
    }

    public static String fmtPrice(BigDecimal p) {
        if (p == null) return "-";
        return p.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String fmt(double v) {
        return String.format("%.2f", v);
    }

    private static QuantSignalDecision pickValid(QuantSignalDecision s, boolean allowNoTradeDirection) {
        if (s == null) return null;
        if (!allowNoTradeDirection && (s.getDirection() == null || "NO_TRADE".equals(s.getDirection()))) return null;
        if (s.getConfidence() == null) return null;
        return s;
    }

    private static QuantSignalDecision copyIfValid(QuantSignalDecision s, boolean allowNoTradeDirection,
                                                   boolean requireStrongDirectional) {
        if (pickValid(s, allowNoTradeDirection) == null) return null;
        if (requireStrongDirectional && !strongDirectional(s)) return null;
        return copySignalWithConfidence(s, s.getConfidence());
    }

    private static boolean strongDirectional(QuantSignalDecision s) {
        return s != null
                && s.getDirection() != null
                && !"NO_TRADE".equals(s.getDirection())
                && s.getConfidence() != null
                && s.getConfidence().doubleValue() >= STRONG_BACKGROUND_CONFIDENCE;
    }

    private static long forecastAgeMinutes(LocalDateTime forecastTime, LocalDateTime now) {
        if (forecastTime == null || now == null) {
            return 0;
        }
        return Duration.between(forecastTime, now).toMinutes();
    }

    private static boolean horizonFresh(QuantSignalDecision s, long ageMinutes) {
        return s != null && horizonFresh(s.getHorizon(), ageMinutes);
    }

    private static boolean horizonFresh(String horizon, long ageMinutes) {
        if (horizon == null || ageMinutes < -ALLOWED_CLOCK_SKEW_MINUTES) {
            return false;
        }
        long maxMinutes = switch (horizon) {
            case "H6" -> 6 * 60L;
            case "H12" -> 12 * 60L;
            case "H24" -> 24 * 60L;
            default -> 0L;
        };
        return maxMinutes > 0 && ageMinutes <= maxMinutes;
    }

    private static QuantSignalDecision copySignalWithConfidence(QuantSignalDecision source, BigDecimal confidence) {
        QuantSignalDecision copy = new QuantSignalDecision();
        copy.setId(source.getId());
        copy.setCycleId(source.getCycleId());
        copy.setHorizon(source.getHorizon());
        copy.setDirection(source.getDirection());
        copy.setConfidence(confidence);
        copy.setMaxLeverage(source.getMaxLeverage());
        copy.setMaxPositionPct(source.getMaxPositionPct());
        copy.setRiskStatus(source.getRiskStatus());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }
}
