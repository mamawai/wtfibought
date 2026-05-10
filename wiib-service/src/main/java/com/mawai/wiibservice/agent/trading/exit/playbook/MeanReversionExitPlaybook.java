package com.mawai.wiibservice.agent.trading.exit.playbook;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPath;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.TradingDecisionContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.getCurrentStopLossPrice;

/**
 * 均值回归仓位的退出剧本。
 *
 * <p>MR 单目标是回到均值，不追求趋势奔跑：先排除“越走越极端”的失败回归，
 * 再在到达中性区全平，或半路到中轨进度时先平半并保护剩余仓位。</p>
 */
final class MeanReversionExitPlaybook implements ExitPlaybook {

    private static final double BOLL_EXTREME_STEP = 5.0;
    private static final double NEUTRAL_LOW = 45.0;
    private static final double NEUTRAL_HIGH = 55.0;
    private static final BigDecimal HALF_CLOSE_RATIO = new BigDecimal("0.50");
    private static final double HALF_MIDLINE_PROGRESS = 0.50;
    private static final double TIME_PROGRESS_R = 0.50;
    private static final long TIME_LIMIT_MINUTES = 30;

    @Override
    public ExitPath path() {
        return ExitPath.MR;
    }

    @Override
    public ExitPlaybookDecision evaluate(TradingDecisionContext decision,
                                         FuturesPositionDTO position,
                                         ExitPlan plan,
                                         LocalDateTime now) {
        if (decision == null || position == null || plan == null || now == null) {
            return ExitPlaybookDecision.holdBlocked("MR数据缺失");
        }
        MarketContext ctx = decision.market();
        if (ctx == null || ctx.price == null || plan.riskPerUnit() == null || plan.riskPerUnit().signum() <= 0) {
            return ExitPlaybookDecision.holdBlocked("MR行情/R缺失");
        }

        boolean isLong = isLong(plan, position);
        BigDecimal profitPerUnit = isLong
                ? ctx.price.subtract(plan.entryPrice())
                : plan.entryPrice().subtract(ctx.price);
        BigDecimal profitR = profitPerUnit.divide(plan.riskPerUnit(), 6, RoundingMode.HALF_UP);
        plan.recordHighestProfitR(profitR.doubleValue());
        long holdMinutes = holdMinutes(plan, position, now);

        // 指标不可靠时跳过 Boll/RSI/高周期趋势判断，只保留时间类保护。
        if (!decision.indicatorExitShielded()) {
            ExitPlaybookDecision invalid = evaluateInvalidation(ctx, plan, isLong);
            if (invalid.actionable()) {
                return invalid;
            }

            if (meanReached(ctx)) {
                return ExitPlaybookDecision.closeFull("MR_MEAN_REACHED");
            }
        }

        Double midlineProgress = decision.indicatorExitShielded() ? null : midlineProgress(ctx, plan, isLong);
        if (!plan.mrMidlineHalfDone()
                && midlineProgress != null
                && midlineProgress >= HALF_MIDLINE_PROGRESS) {
            BigDecimal closeQty = position.getQuantity() != null
                    ? position.getQuantity().multiply(HALF_CLOSE_RATIO).setScale(8, RoundingMode.HALF_DOWN)
                    : BigDecimal.ZERO;
            if (closeQty.signum() > 0) {
                BigDecimal stop = improvesStop(getCurrentStopLossPrice(position), plan.entryPrice(), isLong)
                        ? plan.entryPrice() : null;
                // 当前 SL 已比 entry 更优时不需要再推；此时也不能擅自 markBreakevenDone，
                // 避免 plan 状态和真实 SL 错位（MR 自身用 mrMidlineHalfDone 跟踪进度，不依赖 breakevenDone）。
                boolean wantsBreakeven = stop != null;
                return ExitPlaybookDecision.closePartialAndMoveStop(
                        closeQty, stop, wantsBreakeven, true, "MR_MIDLINE_HALF_PROTECT");
            }
        }

        if (holdMinutes >= timeLimitMinutes(plan) && !hasMeaningfulReversion(midlineProgress, plan)) {
            return ExitPlaybookDecision.closeFull("MR_TIME_EXIT_NO_REVERSION");
        }

        return ExitPlaybookDecision.hold("MR_HOLD");
    }

    private ExitPlaybookDecision evaluateInvalidation(MarketContext ctx, ExitPlan plan, boolean isLong) {
        if (bollMoreExtremeThanEntry(ctx, plan, isLong)) {
            return ExitPlaybookDecision.closeFull("MR_BOLL_MORE_EXTREME");
        }
        if (closedClosesContinueAgainst(ctx, isLong)) {
            return ExitPlaybookDecision.closeFull("MR_THREE_CLOSED_BARS_AGAINST");
        }
        if (newHigherTimeframeTrendAgainst(ctx, plan, isLong)) {
            return ExitPlaybookDecision.closeFull("MR_HIGHER_TF_TREND_AGAINST");
        }
        return ExitPlaybookDecision.hold("MR_VALID");
    }

    private boolean bollMoreExtremeThanEntry(MarketContext ctx, ExitPlan plan, boolean isLong) {
        if (plan.recovered() || ctx.bollPb5m == null || plan.entryBollPb() == null) {
            return false;
        }
        return isLong
                ? ctx.bollPb5m <= plan.entryBollPb() - BOLL_EXTREME_STEP
                : ctx.bollPb5m >= plan.entryBollPb() + BOLL_EXTREME_STEP;
    }

    private boolean closedClosesContinueAgainst(MarketContext ctx, boolean isLong) {
        if (ctx.closeTrendClosed3_5m == null) {
            return false;
        }
        return isLong
                ? "falling_3".equals(ctx.closeTrendClosed3_5m)
                : "rising_3".equals(ctx.closeTrendClosed3_5m);
    }

    private boolean newHigherTimeframeTrendAgainst(MarketContext ctx, ExitPlan plan, boolean isLong) {
        if (plan.recovered() || plan.entryMa1h() == null || plan.entryMa15m() == null
                || ctx.maAlignment1h == null || ctx.maAlignment15m == null) {
            return false;
        }
        boolean currentBothAgainst = directionAgainst(ctx.maAlignment1h, isLong)
                && directionAgainst(ctx.maAlignment15m, isLong);
        boolean entryAlreadyBothAgainst = directionAgainst(plan.entryMa1h(), isLong)
                && directionAgainst(plan.entryMa15m(), isLong);
        return currentBothAgainst && !entryAlreadyBothAgainst;
    }

    private boolean meanReached(MarketContext ctx) {
        if (ctx.bollPb5m != null && ctx.bollPb5m >= NEUTRAL_LOW && ctx.bollPb5m <= NEUTRAL_HIGH) {
            return true;
        }
        if (ctx.rsi5m == null) {
            return false;
        }
        double rsi = ctx.rsi5m.doubleValue();
        return rsi >= NEUTRAL_LOW && rsi <= NEUTRAL_HIGH;
    }

    private Double midlineProgress(MarketContext ctx, ExitPlan plan, boolean isLong) {
        if (plan.recovered() || ctx.bollPb5m == null || plan.entryBollPb() == null) {
            return null;
        }
        double entryPb = plan.entryBollPb();
        if (isLong) {
            if (entryPb >= 50.0) return null;
            return Math.clamp((ctx.bollPb5m - entryPb) / (50.0 - entryPb), 0.0, 1.0);
        }
        if (entryPb <= 50.0) return null;
        return Math.clamp((entryPb - ctx.bollPb5m) / (entryPb - 50.0), 0.0, 1.0);
    }

    private boolean hasMeaningfulReversion(Double midlineProgress, ExitPlan plan) {
        if (plan.mrMidlineHalfDone()) {
            return true;
        }
        if (midlineProgress != null) {
            return midlineProgress >= HALF_MIDLINE_PROGRESS;
        }
        return plan.highestProfitR() >= TIME_PROGRESS_R;
    }

    private boolean directionAgainst(Integer maAlignment, boolean isLong) {
        return maAlignment != null && (isLong ? maAlignment < 0 : maAlignment > 0);
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

    private long timeLimitMinutes(ExitPlan plan) {
        return plan.timeLimit() != null ? plan.timeLimit().toMinutes() : TIME_LIMIT_MINUTES;
    }

    private long holdMinutes(ExitPlan plan, FuturesPositionDTO position, LocalDateTime now) {
        LocalDateTime createdAt = plan.createdAt() != null ? plan.createdAt() : position.getCreatedAt();
        if (createdAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(createdAt, now).toMinutes());
    }
}
