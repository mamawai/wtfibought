package com.mawai.wiibservice.agent.trading;

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
    private static final String LEGACY_PATH_TREND = "TREND";
    private static final String LEGACY_PATH_MR = "MEAN_REVERSION";

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
     * 开仓只取当前 active horizon，避免 10_20/20_30 未来分段提前驱动入场。<br>
     * 每段只在最后1分钟停止新开仓，避免临近过期信号驱动入场。<br>
     * forecastTime=null 兼容回测：退回旧逻辑，从全部信号里按 confidence 选最高。
     */
    public static QuantSignalDecision findBestSignalWithPriority(List<QuantSignalDecision> signals,
                                                          LocalDateTime forecastTime,
                                                          LocalDateTime now) {
        if (signals == null || signals.isEmpty()) return null;

        if (forecastTime != null) {
            long ageMinutes = Duration.between(forecastTime, now).toMinutes();
            String activeHorizon = activeHorizon(ageMinutes);
            if (activeHorizon == null) return null;
            signals = signals.stream()
                    .filter(s -> activeHorizon.equals(s.getHorizon()))
                    .toList();
            if (signals.isEmpty()) return null;
        }

        QuantSignalDecision sig010 = null, sig1020 = null, sig2030 = null;
        for (QuantSignalDecision s : signals) {
            if (s.getHorizon() == null) continue;
            switch (s.getHorizon()) {
                case "0_10" -> sig010 = s;
                case "10_20" -> sig1020 = s;
                case "20_30" -> sig2030 = s;
                default -> {
                }
            }
        }

        QuantSignalDecision primary = null;
        for (QuantSignalDecision s : new QuantSignalDecision[]{sig010, sig1020, sig2030}) {
            QuantSignalDecision valid = pickValid(s);
            if (valid != null && (primary == null
                    || valid.getConfidence().compareTo(primary.getConfidence()) > 0)) {
                primary = valid;
            }
        }
        if (primary == null) return null;

        List<QuantSignalDecision> all = new ArrayList<>();
        if (sig010 != null) all.add(sig010);
        if (sig1020 != null) all.add(sig1020);
        if (sig2030 != null) all.add(sig2030);

        QuantSignalDecision finalPrimary = primary;
        long agree = all.stream()
                .filter(s -> finalPrimary.getDirection().equals(s.getDirection()) && s.getConfidence() != null)
                .count();
        if (agree >= 2 && primary.getConfidence() != null) {
            double buff = agree >= 3 ? 1.15 : 1.08;
            double boosted = Math.min(1.0, primary.getConfidence().doubleValue() * buff);
            primary = copySignalWithConfidence(primary, BigDecimal.valueOf(boosted));
        }

        return primary;
    }

    /** 持仓管理看仍未过期的最高置信度信号，用于反向风险判断。 */
    public static QuantSignalDecision findBestSignal(List<QuantSignalDecision> signals,
                                              LocalDateTime forecastTime,
                                              LocalDateTime now) {
        if (signals == null || signals.isEmpty()) return null;
        if (forecastTime != null) {
            signals = signals.stream().filter(s -> {
                if (s.getHorizon() == null) return true;
                int endMin = switch (s.getHorizon()) {
                    case "0_10" -> 10;
                    case "10_20" -> 20;
                    case "20_30" -> 30;
                    default -> 30;
                };
                return forecastTime.plusMinutes(endMin).isAfter(now);
            }).toList();
            if (signals.isEmpty()) return null;
        }
        QuantSignalDecision best = null;
        for (QuantSignalDecision s : signals) {
            if (s.getDirection() == null || "NO_TRADE".equals(s.getDirection())) continue;
            if (s.getConfidence() == null) continue;
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
        if (targetDistance == null || targetDistance.signum() <= 0 || profit.signum() <= 0) {
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
            targetDistance = ctx.atr5m.multiply(BigDecimal.valueOf(tpAtr));
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

    private static String activeHorizon(long ageMinutes) {
        if (ageMinutes < 0) return null;
        long phaseMinute = ageMinutes % 10;
        if (phaseMinute >= 9) return null;
        if (ageMinutes < 10) return "0_10";
        if (ageMinutes < 20) return "10_20";
        if (ageMinutes < 30) return "20_30";
        return null;
    }

    private static QuantSignalDecision pickValid(QuantSignalDecision s) {
        if (s == null) return null;
        if (s.getDirection() == null || "NO_TRADE".equals(s.getDirection())) return null;
        if (s.getConfidence() == null) return null;
        return s;
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
