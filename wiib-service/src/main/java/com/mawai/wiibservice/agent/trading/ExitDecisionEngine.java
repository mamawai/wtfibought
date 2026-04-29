package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.QuantSignalDecision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.calcCurrentTargetProgress;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.calcTargetDistance;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.currentDateTime;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.findBestSignal;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.fmtPrice;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.getCurrentStopLossPrice;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.getCurrentTakeProfitPrice;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.isTradeSuccess;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.isUpdateSuccess;
import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.syncRemainingRiskOrders;

/**
 * 平仓/持仓保护判断引擎。
 * 硬 SL/TP 仍交给 futures 风控服务；这里处理信号反转、浮盈回撤、时间僵持等软退出。
 */
final class ExitDecisionEngine {

    // 默认动作，表示不做主动平仓或改 SL。
    private static final String ACTION_HOLD = "HOLD";
    // 只收紧止损，不改变仓位数量。
    private static final String ACTION_TIGHTEN_SL = "TIGHTEN_SL";
    // 盈利保护动作，市价平掉部分仓位并同步剩余 SL/TP。
    private static final String ACTION_EXIT_PARTIAL_PROTECT = "EXIT_PARTIAL_PROTECT";
    // 风险退出动作，强反向或风险过高时全平。
    private static final String ACTION_EXIT_FULL_RISK = "EXIT_FULL_RISK";
    // 时间退出动作，持仓太久且没有有效推进时全平。
    private static final String ACTION_TIME_EXIT = "TIME_EXIT";

    // 判定强反向信号所需的最低信号置信度。
    private static final double STRONG_REVERSAL_CONFIDENCE = 0.82;
    // 判定弱反向信号所需的最低信号置信度。
    private static final double SOFT_REVERSAL_CONFIDENCE = 0.65;
    // 触发收紧止损所需的最低 TP 进度。
    private static final double TIGHTEN_SL_PROGRESS = 0.35;
    // 触发保护性平半时，当前盈利至少要达到的 TP 进度。
    private static final double PARTIAL_PROTECT_PROGRESS = 0.45;
    // 触发保护性平半时，历史峰值盈利至少要达到的 TP 进度。
    private static final double PARTIAL_PEAK_PROGRESS = 0.55;
    // 触发保护性平半时，允许的峰值浮盈回撤比例。
    private static final double PARTIAL_PEAK_DRAWDOWN = 0.30;
    // 亏损达到原 SL 距离该比例后，配合风险票提前止血。
    private static final double EARLY_LOSS_SL_PROGRESS = 0.70;
    // 普通时间退出阈值，超过后若盈利推进不足且风险高则退出。
    private static final long TIME_EXIT_MINUTES = 90;
    // 延长时间退出阈值，持仓更久时用较低风险票也可退出。
    private static final long EXTENDED_TIME_EXIT_MINUTES = 180;
    // 保护性平半时关闭的仓位比例。
    private static final BigDecimal PARTIAL_CLOSE_RATIO = new BigDecimal("0.50");

    DeterministicTradingExecutor.ExecutionResult evaluate(
            TradingDecisionContext decision,
            boolean cleanupFromCurrentPositions) {

        List<FuturesPositionDTO> positions = decision.symbolPositions();
        TradingExecutionState state = decision.state();
        MarketContext ctx = decision.market();
        SymbolProfile profile = decision.profile();
        TradingOperations tools = decision.tools();
        LocalDateTime now = currentDateTime(state);

        if (cleanupFromCurrentPositions) {
            Set<Long> livePositionIds = positions.stream()
                    .map(FuturesPositionDTO::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            state.cleanupPositionMemory(livePositionIds);
        }

        StringBuilder execLog = new StringBuilder();
        StringBuilder reasons = new StringBuilder();
        String action = ACTION_HOLD;

        for (FuturesPositionDTO pos : positions) {
            Long positionId = pos.getId();
            if (!"OPEN".equals(pos.getStatus())) {
                if (positionId != null) state.clearPosition(positionId);
                continue;
            }
            BigDecimal entryPrice = pos.getEntryPrice();
            BigDecimal quantity = pos.getQuantity();
            if (entryPrice == null || entryPrice.signum() <= 0 || quantity == null || quantity.signum() <= 0) {
                continue;
            }

            boolean isLong = "LONG".equals(pos.getSide());
            BigDecimal profit = isLong ? ctx.price.subtract(entryPrice) : entryPrice.subtract(ctx.price);

            if (profit.signum() > 0 && positionId != null) {
                state.recordPositiveProfit(positionId, profit);
            }

            QuantSignalDecision bestSignal = findBestSignal(decision.signals(), decision.forecastTime(), now);
            PositionRisk risk = assessRisk(bestSignal, ctx, isLong);
            BigDecimal targetProgress = calcCurrentTargetProgress(pos, profit, ctx, profile);
            BigDecimal targetDistance = calcTargetDistance(pos, ctx, profile);
            BigDecimal stopDistance = calcStopDistance(pos, ctx, profile);
            double peakDrawdown = calcPeakDrawdown(positionId, profit, state);
            BigDecimal peakProgress = calcPeakProgress(positionId, targetDistance, state);
            long holdMinutes = holdingMinutes(pos, now);

            if (positionId == null) {
                reasons.append(pos.getSide()).append(" 缺少positionId→只观察; ");
                continue;
            }

            if (risk.strongReversal() || risk.riskVotes() >= 5) {
                FullCloseResult result = closeFull(tools, state, pos, ACTION_EXIT_FULL_RISK,
                        "强反向/风险票过高", risk, execLog);
                action = mergeAction(action, result.action());
                reasons.append(result.reason());
                continue;
            }

            if (isLossNearStop(profit, stopDistance) && risk.riskVotes() >= 3) {
                FullCloseResult result = closeFull(tools, state, pos, ACTION_EXIT_FULL_RISK,
                        "亏损接近SL且风险升高", risk, execLog);
                action = mergeAction(action, result.action());
                reasons.append(result.reason());
                continue;
            }

            TradingExecutionState.PositionPeak peak = state.getPositionPeak(positionId);
            if (shouldPartialProtect(targetProgress, peakProgress, peakDrawdown, risk, peak)) {
                String closeResult = closePartial(tools, state, pos, targetProgress, peakDrawdown, risk, execLog);
                if (closeResult != null) {
                    action = mergeAction(action, ACTION_EXIT_PARTIAL_PROTECT);
                    reasons.append(closeResult);
                    continue;
                }
            }

            if (shouldTimeExit(holdMinutes, targetProgress, risk)) {
                FullCloseResult result = closeFull(tools, state, pos, ACTION_TIME_EXIT,
                        "持仓僵持超时", risk, execLog);
                action = mergeAction(action, result.action());
                reasons.append(result.reason());
                continue;
            }

            if (shouldTightenStop(targetProgress, risk)) {
                String tightenResult = tightenStopLoss(tools, pos, profit, ctx, isLong, risk, execLog);
                if (tightenResult != null) {
                    action = mergeAction(action, ACTION_TIGHTEN_SL);
                    reasons.append(tightenResult);
                    continue;
                }
            }

            if (reasons.isEmpty()) {
                String pnlStr = pos.getUnrealizedPnlPct() != null
                        ? pos.getUnrealizedPnlPct().setScale(2, RoundingMode.HALF_UP) + "%" : "N/A";
                reasons.append(pos.getSide())
                        .append(" pnl=").append(pnlStr)
                        .append(" 进度=").append(formatProgress(targetProgress))
                        .append(" peak回撤=").append(String.format("%.0f%%", peakDrawdown * 100))
                        .append(" 风险票=").append(risk.riskVotes())
                        .append(" ").append(analyzePosition(ctx))
                        .append("→持有; ");
            }
        }

        if (reasons.isEmpty()) reasons.append("无活跃持仓");
        return new DeterministicTradingExecutor.ExecutionResult(action, reasons.toString().trim(), execLog.toString());
    }

    private PositionRisk assessRisk(QuantSignalDecision signal, MarketContext ctx, boolean isLong) {
        int marketVotes = 0;
        int signalVotes = 0;
        List<String> reasons = new ArrayList<>();

        if ("SHOCK".equals(ctx.regime)) {
            marketVotes += 2;
            reasons.add("SHOCK");
        }
        if (ctx.maAlignment1h != null && ((isLong && ctx.maAlignment1h < 0) || (!isLong && ctx.maAlignment1h > 0))) {
            marketVotes += 2;
            reasons.add("1h反向");
        }
        if (ctx.maAlignment15m != null && ((isLong && ctx.maAlignment15m < 0) || (!isLong && ctx.maAlignment15m > 0))) {
            marketVotes++;
            reasons.add("15m反向");
        }
        if (ctx.maAlignment5m != null && ((isLong && ctx.maAlignment5m < 0) || (!isLong && ctx.maAlignment5m > 0))) {
            marketVotes++;
            reasons.add("5m反向");
        }
        if (("death".equals(ctx.macdCross5m) && isLong) || ("golden".equals(ctx.macdCross5m) && !isLong)) {
            marketVotes++;
            reasons.add("MACD交叉反向");
        }
        if (isMacdHistAgainst(ctx.macdHistTrend5m, isLong)) {
            marketVotes++;
            reasons.add("MACD柱反向");
        }
        if (ctx.closeTrend5m != null) {
            boolean closeAgainst = isLong ? ctx.closeTrend5m.contains("falling") : ctx.closeTrend5m.contains("rising");
            if (closeAgainst) {
                marketVotes++;
                reasons.add("收盘趋势反向");
            }
        }

        double micro = 0;
        if (ctx.bidAskImbalance != null) micro += ctx.bidAskImbalance;
        if (ctx.takerPressure != null) micro += ctx.takerPressure;
        if ((isLong && micro < -0.15) || (!isLong && micro > 0.15)) {
            marketVotes++;
            reasons.add("微结构反向");
        }
        if (ctx.lsrExtreme != null && ((isLong && ctx.lsrExtreme > 0.4) || (!isLong && ctx.lsrExtreme < -0.4))) {
            marketVotes++;
            reasons.add("多空比拥挤");
        }
        if (ctx.qualityFlags.contains("LOW_CONFIDENCE") || ctx.qualityFlags.contains("STALE_AGG_TRADE")) {
            marketVotes++;
            reasons.add("数据质量风险");
        }

        boolean oppositeDirection = false;
        double confidence = 0;
        if (signal != null && signal.getDirection() != null && signal.getConfidence() != null) {
            oppositeDirection = (isLong && "SHORT".equals(signal.getDirection()))
                    || (!isLong && "LONG".equals(signal.getDirection()));
            confidence = signal.getConfidence().doubleValue();
        }
        if (oppositeDirection && confidence >= STRONG_REVERSAL_CONFIDENCE) {
            signalVotes += 2;
            reasons.add("强反向信号");
        } else if (oppositeDirection && confidence >= SOFT_REVERSAL_CONFIDENCE) {
            signalVotes++;
            reasons.add("弱反向信号");
        }

        boolean strongReversal = oppositeDirection
                && confidence >= STRONG_REVERSAL_CONFIDENCE
                && marketVotes >= 2;
        return new PositionRisk(marketVotes + signalVotes, strongReversal, String.join(",", reasons));
    }

    private boolean shouldPartialProtect(BigDecimal targetProgress,
                                         BigDecimal peakProgress,
                                         double peakDrawdown,
                                         PositionRisk risk,
                                         TradingExecutionState.PositionPeak peak) {
        if (peak == null || peak.partialTpDone()) return false;
        if (targetProgress == null || peakProgress == null) return false;
        return targetProgress.compareTo(BigDecimal.valueOf(PARTIAL_PROTECT_PROGRESS)) >= 0
                && peakProgress.compareTo(BigDecimal.valueOf(PARTIAL_PEAK_PROGRESS)) >= 0
                && peakDrawdown >= PARTIAL_PEAK_DRAWDOWN
                && risk.riskVotes() >= 2;
    }

    private boolean shouldTimeExit(long holdMinutes, BigDecimal targetProgress, PositionRisk risk) {
        BigDecimal progress = targetProgress != null ? targetProgress : BigDecimal.ZERO;
        boolean normalTimeout = holdMinutes >= TIME_EXIT_MINUTES
                && progress.compareTo(new BigDecimal("0.20")) < 0
                && risk.riskVotes() >= 3;
        boolean extendedTimeout = holdMinutes >= EXTENDED_TIME_EXIT_MINUTES
                && progress.compareTo(new BigDecimal("0.35")) < 0
                && risk.riskVotes() >= 2;
        return normalTimeout || extendedTimeout;
    }

    private boolean shouldTightenStop(BigDecimal targetProgress, PositionRisk risk) {
        return targetProgress != null
                && targetProgress.compareTo(BigDecimal.valueOf(TIGHTEN_SL_PROGRESS)) >= 0
                && risk.riskVotes() >= 2;
    }

    private String closePartial(TradingOperations tools,
                                TradingExecutionState state,
                                FuturesPositionDTO pos,
                                BigDecimal targetProgress,
                                double peakDrawdown,
                                PositionRisk risk,
                                StringBuilder execLog) {
        BigDecimal originalQty = pos.getQuantity();
        BigDecimal closeQty = originalQty.multiply(PARTIAL_CLOSE_RATIO).setScale(8, RoundingMode.HALF_DOWN);
        BigDecimal remainingQty = originalQty.subtract(closeQty).setScale(8, RoundingMode.HALF_DOWN);
        if (closeQty.signum() <= 0 || remainingQty.signum() <= 0) return null;

        BigDecimal currentSl = getCurrentStopLossPrice(pos);
        BigDecimal currentTp = getCurrentTakeProfitPrice(pos);
        String closeResult = tools.closePosition(pos.getId(), closeQty);
        execLog.append("保护性平半: ").append(closeResult).append("\n");
        if (!isTradeSuccess(closeResult)) {
            return "保护性平半失败→继续等TP/SL; ";
        }

        state.markPartialTpDone(pos.getId());
        state.clearReversalStreak(pos.getId());
        execLog.append(syncRemainingRiskOrders(tools, pos.getId(), currentSl, currentTp, remainingQty));
        return String.format("盈利进度%.0f%%+峰值回撤%.0f%%+风险票%d(%s)→平50%%保护; ",
                targetProgress.multiply(BigDecimal.valueOf(100)).doubleValue(),
                peakDrawdown * 100,
                risk.riskVotes(),
                risk.detail());
    }

    private String tightenStopLoss(TradingOperations tools,
                                   FuturesPositionDTO pos,
                                   BigDecimal profit,
                                   MarketContext ctx,
                                   boolean isLong,
                                   PositionRisk risk,
                                   StringBuilder execLog) {
        BigDecimal candidate = calcProtectiveStop(pos.getEntryPrice(), profit, ctx, isLong);
        BigDecimal currentSl = getCurrentStopLossPrice(pos);
        if (!improvesStop(currentSl, candidate, isLong)) {
            return null;
        }

        String result = tools.setStopLoss(pos.getId(), candidate, pos.getQuantity());
        execLog.append("收紧止损: ").append(result).append("\n");
        if (!isUpdateSuccess(result)) {
            return "收紧SL失败→继续持有; ";
        }
        return String.format("盈利进度≥%.0f%%+风险票%d(%s)→SL收紧到%s; ",
                TIGHTEN_SL_PROGRESS * 100,
                risk.riskVotes(),
                risk.detail(),
                fmtPrice(candidate));
    }

    private FullCloseResult closeFull(TradingOperations tools,
                                      TradingExecutionState state,
                                      FuturesPositionDTO pos,
                                      String action,
                                      String reason,
                                      PositionRisk risk,
                                      StringBuilder execLog) {
        String result = tools.closePosition(pos.getId(), pos.getQuantity());
        execLog.append(reason).append("全平: ").append(result).append("\n");
        if (isTradeSuccess(result)) {
            state.clearPosition(pos.getId());
            return new FullCloseResult(action,
                    reason + "+风险票" + risk.riskVotes() + "(" + risk.detail() + ")→全平; ");
        }
        return new FullCloseResult(ACTION_HOLD, reason + "全平失败→继续等SL/TP; ");
    }

    private BigDecimal calcStopDistance(FuturesPositionDTO pos, MarketContext ctx, SymbolProfile profile) {
        BigDecimal entry = pos.getEntryPrice();
        BigDecimal sl = getCurrentStopLossPrice(pos);
        BigDecimal distance = sl != null && entry != null ? sl.subtract(entry).abs() : null;
        if (distance != null && distance.signum() > 0) return distance;

        double slAtr = switch (TradingDecisionSupport.normalizeStrategyPath(pos.getMemo())) {
            case TradingDecisionSupport.PATH_BREAKOUT -> profile.breakoutSlAtr();
            case TradingDecisionSupport.PATH_MR -> profile.revertSlAtr();
            default -> profile.trendSlAtr();
        };
        return ctx.atr5m.multiply(BigDecimal.valueOf(slAtr));
    }

    private boolean isLossNearStop(BigDecimal profit, BigDecimal stopDistance) {
        if (profit == null || profit.signum() >= 0) return false;
        if (stopDistance == null || stopDistance.signum() <= 0) return false;
        BigDecimal lossProgress = profit.abs().divide(stopDistance, 4, RoundingMode.HALF_UP);
        return lossProgress.compareTo(BigDecimal.valueOf(EARLY_LOSS_SL_PROGRESS)) >= 0;
    }

    private double calcPeakDrawdown(Long positionId, BigDecimal profit, TradingExecutionState state) {
        if (positionId == null) return 0;
        BigDecimal peakProfit = state.getPositionMaxProfit(positionId);
        if (peakProfit == null || peakProfit.signum() <= 0) return 0;
        BigDecimal drawdown = peakProfit.subtract(profit);
        if (drawdown.signum() <= 0) return 0;
        return drawdown.divide(peakProfit, 6, RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal calcPeakProgress(Long positionId, BigDecimal targetDistance, TradingExecutionState state) {
        if (positionId == null || targetDistance == null || targetDistance.signum() <= 0) return null;
        BigDecimal peakProfit = state.getPositionMaxProfit(positionId);
        if (peakProfit == null || peakProfit.signum() <= 0) return null;
        return peakProfit.divide(targetDistance, 4, RoundingMode.HALF_UP);
    }

    private long holdingMinutes(FuturesPositionDTO pos, LocalDateTime now) {
        if (pos.getCreatedAt() == null) return 0;
        long minutes = Duration.between(pos.getCreatedAt(), now).toMinutes();
        return Math.max(0, minutes);
    }

    private BigDecimal calcProtectiveStop(BigDecimal entry, BigDecimal profit, MarketContext ctx, boolean isLong) {
        BigDecimal lockByProfit = profit.multiply(new BigDecimal("0.20")).max(BigDecimal.ZERO);
        BigDecimal lockByAtr = ctx.atr5m.multiply(new BigDecimal("0.15"));
        BigDecimal lock = lockByProfit.min(lockByAtr).setScale(8, RoundingMode.HALF_UP);
        return isLong ? entry.add(lock) : entry.subtract(lock);
    }

    private boolean improvesStop(BigDecimal currentSl, BigDecimal candidate, boolean isLong) {
        if (candidate == null || candidate.signum() <= 0) return false;
        if (currentSl == null || currentSl.signum() <= 0) return true;
        return isLong ? candidate.compareTo(currentSl) > 0 : candidate.compareTo(currentSl) < 0;
    }

    private boolean isMacdHistAgainst(String trend, boolean isLong) {
        if (trend == null) return false;
        return isLong
                ? (trend.startsWith("falling") || "mostly_down".equals(trend))
                : (trend.startsWith("rising") || "mostly_up".equals(trend));
    }

    private String analyzePosition(MarketContext ctx) {
        StringBuilder sb = new StringBuilder("[");
        if (ctx.maAlignment1h != null) sb.append("1h趋势=").append(ctx.maAlignment1h > 0 ? "多" : ctx.maAlignment1h < 0 ? "空" : "平");
        if (ctx.macdHistTrend5m != null) sb.append(" MACD=").append(ctx.macdHistTrend5m);
        if (ctx.volumeRatio5m != null) sb.append(String.format(" Vol=%.1fx", ctx.volumeRatio5m));
        if (ctx.bollPb5m != null) sb.append(String.format(" BB=%.0f%%", ctx.bollPb5m));
        sb.append("]");
        return sb.toString();
    }

    private String formatProgress(BigDecimal progress) {
        if (progress == null) return "N/A";
        return String.format("%.0f%%", progress.multiply(BigDecimal.valueOf(100)).doubleValue());
    }

    private String mergeAction(String current, String next) {
        return actionRank(next) > actionRank(current) ? next : current;
    }

    private int actionRank(String action) {
        return switch (action) {
            case ACTION_EXIT_FULL_RISK -> 4;
            case ACTION_TIME_EXIT -> 3;
            case ACTION_EXIT_PARTIAL_PROTECT -> 2;
            case ACTION_TIGHTEN_SL -> 1;
            default -> 0;
        };
    }

    private record PositionRisk(int riskVotes, boolean strongReversal, String detail) {
    }

    private record FullCloseResult(String action, String reason) {
    }
}
