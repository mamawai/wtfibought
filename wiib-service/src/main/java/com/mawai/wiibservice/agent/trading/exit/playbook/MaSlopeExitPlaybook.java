package com.mawai.wiibservice.agent.trading.exit.playbook;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPath;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeFailureEvaluator;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeFailureEvaluator.FailureScore;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MaState;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.TradingDecisionContext;
import com.mawai.wiibservice.agent.trading.runtime.TradingExecutionState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

import static com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.getCurrentStopLossPrice;

/**
 * MaSlope 仓位退出剧本。
 * 主动退出只认 MaSlope 自身状态机；保本、部分止盈和 ATR trail 负责价格保护。
 */
final class MaSlopeExitPlaybook implements ExitPlaybook {

    // 60d ETH 回测：4 笔 MFE≥0.5R 被 FastFail 误杀，保本门槛从 1R 提前到 0.5R 锁住"擦肩盈利"。
    private static final BigDecimal BREAKEVEN_R = new BigDecimal("0.50");
    private static final BigDecimal TRAIL_R = new BigDecimal("2.00");
    private static final BigDecimal PARTIAL_R = new BigDecimal("3.00");
    private static final int PARTIAL_R_MILESTONE = 300;
    private static final BigDecimal PARTIAL_CLOSE_RATIO = new BigDecimal("0.30");
    private static final BigDecimal TIME_PROGRESS_R = new BigDecimal("0.50");
    private static final long TIME_LIMIT_MINUTES = 90;
    private static final BigDecimal ATR_TRAIL_MULT = new BigDecimal("2.0");
    private static final BigDecimal FAST_FAIL_DEEP_R = new BigDecimal("-0.45");
    private static final int FAST_FAIL_DEEP_SCORE = 2;
    private static final int FAST_FAIL_SHALLOW_SCORE = 3;
    private static final int FAST_FAIL_STREAK_THRESHOLD = 2;
    private static final BigDecimal EARLY_FAIL_R = BigDecimal.ZERO;
    private static final long EARLY_FAIL_MINUTES = 60;
    // 60d ETH 回测：A 类 13 笔"开仓即反向"亏损，主周期前 3 根（9 分钟）内 MAE 已到 -0.2R 但 MFE<0.15R 时早平。
    private static final long EARLY_KILL_MINUTES = 9;
    private static final double EARLY_KILL_MFE_R = 0.15;
    private static final BigDecimal EARLY_KILL_MAE_R = new BigDecimal("-0.20");
    private static final int EARLY_KILL_SCORE = 2;
    // 入场要求 0.03 ATR；退出用 -0.005/+0.005 迟滞，避免 0 附近抖动来回平仓。
    private static final double MA7_EXIT_SLOPE_ATR = -0.005;
    private static final int EXTINGUISH_STREAK_THRESHOLD = 2;

    @Override
    public ExitPath path() {
        return ExitPath.MA_SLOPE;
    }

    @Override
    public ExitPlaybookDecision evaluate(TradingDecisionContext decision,
                                         FuturesPositionDTO position,
                                         ExitPlan plan,
                                         LocalDateTime now) {
        if (decision == null || position == null || plan == null || now == null) {
            return ExitPlaybookDecision.holdBlocked("MA_SLOPE数据缺失");
        }
        MarketContext ctx = decision.market();
        if (ctx == null || ctx.price == null || plan.entryPrice() == null
                || plan.riskPerUnit() == null || plan.riskPerUnit().signum() <= 0) {
            return ExitPlaybookDecision.holdBlocked("MA_SLOPE行情/R缺失");
        }

        boolean isLong = isLong(plan, position);
        BigDecimal profitPerUnit = isLong
                ? ctx.price.subtract(plan.entryPrice())
                : plan.entryPrice().subtract(ctx.price);
        BigDecimal profitR = profitPerUnit.divide(plan.riskPerUnit(), 6, RoundingMode.HALF_UP);
        plan.recordHighestProfitR(profitR.doubleValue());
        long holdMinutes = holdMinutes(plan, position, now);
        boolean breakevenDone = syncBreakevenDoneIfProtected(plan, position, isLong);

        if (!breakevenDone && profitR.compareTo(BREAKEVEN_R) >= 0) {
            BigDecimal stop = plan.entryPrice();
            if (improvesStop(getCurrentStopLossPrice(position), stop, isLong)) {
                return ExitPlaybookDecision.moveStop(stop, true, "MA_SLOPE_BREAKEVEN_1R");
            }
            plan.markBreakevenDone();
            breakevenDone = true;
            return ExitPlaybookDecision.hold("MA_SLOPE_BREAKEVEN_ALREADY_PROTECTED");
        }

        MaState current = hasSignalData(ctx) ? MaSlopeStateClassifier.classifyPrimary(ctx) : null;
        MaState confirm = hasConfirmSignalData(ctx) ? MaSlopeStateClassifier.classifyConfirm(ctx) : null;
        boolean higherTrendStillSupports = higherTrendStillSupports(confirm, isLong);

        // 开仓后 9 分钟内若 MAE 已到 -0.20R 且全程 MFE<0.15R，主周期失败分≥2 直接早平，
        // 避免 A 类"开仓即反向"硬扛到 FastFail 评估窗口。
        ExitPlaybookDecision earlyKill = evaluateEarlyKill(
                decision, breakevenDone, holdMinutes, plan, current, profitR, isLong, ctx);
        if (earlyKill.actionable() || earlyKill.entryEvaluationBlocked()) {
            return earlyKill;
        }

        ExitPlaybookDecision fastFail = evaluateFastFail(
                decision, position, plan, breakevenDone, current, confirm, profitR, isLong, ctx);
        if (fastFail.actionable() || fastFail.entryEvaluationBlocked()) {
            return fastFail;
        }
        boolean fastFailWaiting = fastFail.reason() != null
                && fastFail.reason().startsWith("MA_SLOPE_FAST_FAIL_WAIT");
        if (fastFailWaiting) {
            return fastFail;
        }

        if (!decision.indicatorExitShielded() && current != null && current.stronglyAgainst(isLong)
                && !higherTrendStillSupports) {
            clearStreak(decision, position);
            clearFastFailStreak(decision, position);
            return ExitPlaybookDecision.closeFull("MA_SLOPE_SIGNAL_REVERSE state=" + current.state());
        }

        ExitPlaybookDecision extinguish = evaluateExtinguish(
                decision, position, breakevenDone, current, higherTrendStillSupports, isLong, ctx);
        if (extinguish.actionable() || extinguish.entryEvaluationBlocked()) {
            return extinguish;
        }
        boolean extinguishWaiting = extinguish.reason() != null
                && extinguish.reason().startsWith("MA_SLOPE_EXTINGUISH_WAIT");

        if (!breakevenDone
                && !plan.recovered()
                && holdMinutes >= EARLY_FAIL_MINUTES
                && profitR.compareTo(EARLY_FAIL_R) < 0
                && !signalStillStrong(decision, current, isLong)
                && !higherTrendStillSupports) {
            clearStreak(decision, position);
            clearFastFailStreak(decision, position);
            return ExitPlaybookDecision.closeFull("MA_SLOPE_NO_PROGRESS_IN_60M");
        }

        if (profitR.compareTo(PARTIAL_R) >= 0 && !plan.isPartialDoneAtR(PARTIAL_R_MILESTONE)) {
            BigDecimal closeQty = position.getQuantity() != null
                    ? position.getQuantity().multiply(PARTIAL_CLOSE_RATIO).setScale(8, RoundingMode.HALF_DOWN)
                    : BigDecimal.ZERO;
            if (closeQty.signum() > 0) {
                BigDecimal trail = atrTrailStop(ctx, isLong);
                BigDecimal stop = improvesStop(getCurrentStopLossPrice(position), trail, isLong) ? trail : null;
                return ExitPlaybookDecision.closePartialAndMoveStop(
                        closeQty, stop, PARTIAL_R_MILESTONE, "MA_SLOPE_PARTIAL_3R");
            }
        }

        if (profitR.compareTo(TRAIL_R) >= 0) {
            BigDecimal trail = atrTrailStop(ctx, isLong);
            if (improvesStop(getCurrentStopLossPrice(position), trail, isLong)) {
                return ExitPlaybookDecision.moveStop(trail, "MA_SLOPE_ATR_TRAIL_2R");
            }
        }

        if (!plan.recovered()
                && holdMinutes >= TIME_LIMIT_MINUTES
                && plan.highestProfitR() < TIME_PROGRESS_R.doubleValue()
                && !higherTrendStillSupports) {
            clearStreak(decision, position);
            clearFastFailStreak(decision, position);
            return ExitPlaybookDecision.closeFull("MA_SLOPE_NO_0_5R_IN_90M");
        }

        return extinguishWaiting ? extinguish : ExitPlaybookDecision.hold("MA_SLOPE_HOLD");
    }

    private ExitPlaybookDecision evaluateEarlyKill(TradingDecisionContext decision,
                                                   boolean breakevenDone,
                                                   long holdMinutes,
                                                   ExitPlan plan,
                                                   MaState current,
                                                   BigDecimal profitR,
                                                   boolean isLong,
                                                   MarketContext ctx) {
        if (decision.indicatorExitShielded() || breakevenDone || current == null) {
            return ExitPlaybookDecision.hold("MA_SLOPE_EARLY_KILL_VALID");
        }
        if (holdMinutes > EARLY_KILL_MINUTES) {
            return ExitPlaybookDecision.hold("MA_SLOPE_EARLY_KILL_WINDOW_PASSED");
        }
        if (plan.highestProfitR() >= EARLY_KILL_MFE_R) {
            return ExitPlaybookDecision.hold("MA_SLOPE_EARLY_KILL_MFE_OK");
        }
        if (profitR.compareTo(EARLY_KILL_MAE_R) > 0) {
            return ExitPlaybookDecision.hold("MA_SLOPE_EARLY_KILL_MAE_OK");
        }
        FailureScore failure = MaSlopeFailureEvaluator.score(ctx, current, isLong, plan.entryMa7SlopeAtr());
        if (failure.score() < EARLY_KILL_SCORE) {
            return ExitPlaybookDecision.hold("MA_SLOPE_EARLY_KILL_SCORE_OK score=" + failure.score());
        }
        return ExitPlaybookDecision.closeFull(String.format(Locale.US,
                "MA_SLOPE_EARLY_KILL score=%d r=%.2f mfe=%.2f reasons=%s",
                failure.score(), profitR.doubleValue(), plan.highestProfitR(), failure.reasonText()));
    }

    private ExitPlaybookDecision evaluateFastFail(TradingDecisionContext decision,
                                                  FuturesPositionDTO position,
                                                  ExitPlan plan,
                                                  boolean breakevenDone,
                                                  MaState current,
                                                  MaState confirm,
                                                  BigDecimal profitR,
                                                  boolean isLong,
                                                  MarketContext ctx) {
        if (decision.indicatorExitShielded() || breakevenDone || current == null) {
            clearFastFailStreak(decision, position);
            return ExitPlaybookDecision.hold("MA_SLOPE_FAST_FAIL_VALID");
        }

        FailureScore failure = MaSlopeFailureEvaluator.score(ctx, current, isLong, plan.entryMa7SlopeAtr());
        boolean higherTrendAddsThreshold = higherTrendStillSupports(confirm, isLong)
                || EntryStrategySupportForExit.directionAligns(ctx.maAlignment1h, isLong);
        if (profitR.compareTo(FAST_FAIL_DEEP_R) <= 0) {
            int threshold = FAST_FAIL_DEEP_SCORE + (higherTrendAddsThreshold ? 1 : 0);
            if (failure.score() >= threshold) {
                clearFastFailStreak(decision, position);
                return ExitPlaybookDecision.closeFull(fastFailReason(
                        "MA_SLOPE_FAST_FAIL", failure, threshold, profitR, 0));
            }
            clearFastFailStreak(decision, position);
            return ExitPlaybookDecision.hold("MA_SLOPE_FAST_FAIL_VALID");
        }

        if (profitR.compareTo(BigDecimal.ZERO) < 0) {
            int threshold = FAST_FAIL_SHALLOW_SCORE + (higherTrendAddsThreshold ? 1 : 0);
            if (failure.score() >= threshold) {
                TradingExecutionState state = decision.state();
                if (state == null || position.getId() == null) {
                    return ExitPlaybookDecision.holdBlocked("MA_SLOPE_FAST_FAIL_STREAK_STATE_MISSING");
                }
                int streak = state.recordFastFailStreakOnSignal(position.getId(),
                        signalKey(ctx, current) + ":fastFail:" + failure.score());
                if (streak >= FAST_FAIL_STREAK_THRESHOLD) {
                    return ExitPlaybookDecision.closeFull(fastFailReason(
                            "MA_SLOPE_FAST_FAIL", failure, threshold, profitR, streak));
                }
                return ExitPlaybookDecision.hold(fastFailReason(
                        "MA_SLOPE_FAST_FAIL_WAIT", failure, threshold, profitR, streak));
            }
        }

        clearFastFailStreak(decision, position);
        return ExitPlaybookDecision.hold("MA_SLOPE_FAST_FAIL_VALID");
    }

    private String fastFailReason(String prefix,
                                  FailureScore failure,
                                  int threshold,
                                  BigDecimal profitR,
                                  int streak) {
        String reason = String.format(Locale.US, "%s score=%d threshold=%d r=%.2f reasons=%s",
                prefix, failure.score(), threshold, profitR.doubleValue(), failure.reasonText());
        return streak > 0 ? reason + " streak=" + streak : reason;
    }

    private ExitPlaybookDecision evaluateExtinguish(TradingDecisionContext decision,
                                                    FuturesPositionDTO position,
                                                    boolean breakevenDone,
                                                    MaState current,
                                                    boolean higherTrendStillSupports,
                                                    boolean isLong,
                                                    MarketContext ctx) {
        if (decision.indicatorExitShielded()) {
            clearStreak(decision, position);
            return ExitPlaybookDecision.hold("MA_SLOPE_SIGNAL_SHIELDED");
        }
        if (higherTrendStillSupports) {
            clearStreak(decision, position);
            return ExitPlaybookDecision.hold("MA_SLOPE_PULLBACK_WITH_HIGHER_TREND");
        }
        if (!breakevenDone || current == null
                || !current.extinguishingAgainstPosition(isLong, MA7_EXIT_SLOPE_ATR)) {
            clearStreak(decision, position);
            return ExitPlaybookDecision.hold("MA_SLOPE_EXTINGUISH_VALID");
        }
        TradingExecutionState state = decision.state();
        if (state == null || position.getId() == null) {
            return ExitPlaybookDecision.holdBlocked("MA_SLOPE_STREAK_STATE_MISSING");
        }
        int streak = state.recordReversalStreakOnSignal(position.getId(), signalKey(ctx, current));
        if (streak >= EXTINGUISH_STREAK_THRESHOLD) {
            return ExitPlaybookDecision.closeFull(
                    "MA_SLOPE_EXTINGUISH state=" + current.state() + " streak=" + streak);
        }
        return ExitPlaybookDecision.hold(
                "MA_SLOPE_EXTINGUISH_WAIT state=" + current.state() + " streak=" + streak);
    }

    private boolean hasSignalData(MarketContext ctx) {
        return ctx != null
                && !ctx.atrFromFallback
                && ctx.price != null && ctx.price.signum() > 0
                && ctx.atrClosed != null && ctx.atrClosed.signum() > 0
                && ctx.ma7SeriesClosed.size() >= MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM
                && ctx.ma25SeriesClosed.size() >= MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM;
    }

    private boolean hasConfirmSignalData(MarketContext ctx) {
        return ctx != null
                && ctx.confirmAtr != null && ctx.confirmAtr.signum() > 0
                && ctx.confirmMa7SeriesClosed.size() >= MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM
                && ctx.confirmMa25SeriesClosed.size() >= MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM;
    }

    private boolean higherTrendStillSupports(MaState confirm, boolean isLong) {
        return confirm != null && confirm.sameBroadDirection(isLong);
    }

    private boolean syncBreakevenDoneIfProtected(ExitPlan plan, FuturesPositionDTO position, boolean isLong) {
        if (plan.breakevenDone()) {
            return true;
        }
        BigDecimal currentSl = getCurrentStopLossPrice(position);
        if (currentSl == null || currentSl.signum() <= 0 || plan.entryPrice() == null) {
            return false;
        }
        boolean protectedAtEntry = isLong
                ? currentSl.compareTo(plan.entryPrice()) >= 0
                : currentSl.compareTo(plan.entryPrice()) <= 0;
        if (protectedAtEntry) {
            plan.markBreakevenDone();
        }
        return protectedAtEntry;
    }

    private boolean signalStillStrong(TradingDecisionContext decision, MaState current, boolean isLong) {
        return !decision.indicatorExitShielded()
                && current != null
                && current.sameStrongDirection(isLong);
    }

    private String signalKey(MarketContext ctx, MaState current) {
        if (ctx.lastClosedBarKey != null && !ctx.lastClosedBarKey.isBlank()) {
            return ctx.decisionInterval.getCode() + ":" + ctx.lastClosedBarKey;
        }
        BigDecimal ma7Last = ctx.ma7SeriesClosed.isEmpty() ? null : ctx.ma7SeriesClosed.getLast();
        BigDecimal ma25Last = ctx.ma25SeriesClosed.isEmpty() ? null : ctx.ma25SeriesClosed.getLast();
        // 没有交易所时间戳的测试/旧快照，用闭合序列末值+状态做去重，至少避免同一快照重复累加。
        return String.format(Locale.US, "%s:%s:%s:%s:%.4f",
                ctx.decisionInterval.getCode(),
                current.state(),
                ma7Last != null ? ma7Last.toPlainString() : "na",
                ma25Last != null ? ma25Last.toPlainString() : "na",
                current.ma7SlopeAtr());
    }

    private void clearStreak(TradingDecisionContext decision, FuturesPositionDTO position) {
        if (decision != null && decision.state() != null && position != null && position.getId() != null) {
            decision.state().clearReversalStreak(position.getId());
        }
    }

    private void clearFastFailStreak(TradingDecisionContext decision, FuturesPositionDTO position) {
        if (decision != null && decision.state() != null && position != null && position.getId() != null) {
            decision.state().clearFastFailStreak(position.getId());
        }
    }

    private BigDecimal atrTrailStop(MarketContext ctx, boolean isLong) {
        BigDecimal atr = ctx.atrClosed;
        if (atr == null || atr.signum() <= 0) {
            atr = ctx.atr;
        }
        if (atr == null || atr.signum() <= 0) {
            return null;
        }
        BigDecimal gap = atr.multiply(ATR_TRAIL_MULT);
        return isLong ? ctx.price.subtract(gap) : ctx.price.add(gap);
    }

    private boolean improvesStop(BigDecimal currentSl, BigDecimal candidate, boolean isLong) {
        if (candidate == null || candidate.signum() <= 0) return false;
        if (currentSl == null || currentSl.signum() <= 0) return true;
        return isLong ? candidate.compareTo(currentSl) > 0 : candidate.compareTo(currentSl) < 0;
    }

    private boolean isLong(ExitPlan plan, FuturesPositionDTO position) {
        String side = plan.side() != null ? plan.side() : position.getSide();
        return "LONG".equals(side);
    }

    private long holdMinutes(ExitPlan plan, FuturesPositionDTO position, LocalDateTime now) {
        LocalDateTime createdAt = plan.createdAt() != null ? plan.createdAt() : position.getCreatedAt();
        if (createdAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(createdAt, now).toMinutes());
    }

    private static final class EntryStrategySupportForExit {
        private EntryStrategySupportForExit() {
        }

        private static boolean directionAligns(Integer alignment, boolean isLong) {
            if (alignment == null) return false;
            return isLong ? alignment > 0 : alignment < 0;
        }
    }
}
