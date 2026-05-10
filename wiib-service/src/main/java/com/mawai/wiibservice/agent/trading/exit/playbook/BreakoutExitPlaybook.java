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
 * 突破仓位的退出剧本。
 *
 * <p>突破单讲究启动后的推进速度：先处理 1R 保本；保本前重点看突破是否快速失效；
 * 保本后才看结构反转、2R 部分止盈和吊灯止损。</p>
 */
final class BreakoutExitPlaybook implements ExitPlaybook {

    private static final BigDecimal BREAKEVEN_R = BigDecimal.ONE;
    private static final BigDecimal BREAKEVEN_LOCK_R = new BigDecimal("0.10");
    private static final BigDecimal FAIL_TO_PROGRESS_R = new BigDecimal("0.50");
    private static final BigDecimal PARTIAL_R = new BigDecimal("2.00");
    private static final int PARTIAL_R_MILESTONE = 200;
    private static final BigDecimal PARTIAL_CLOSE_RATIO = new BigDecimal("0.30");
    private static final long FAIL_TO_PROGRESS_MINUTES = 15;
    private static final int VOLUME_EXHAUSTION_MIN_BARS = 3;
    private static final double VOLUME_EXHAUSTION_RATIO = 0.8;
    private static final BigDecimal CHANDELIER_ATR_MULT = new BigDecimal("2.5");

    @Override
    public ExitPath path() {
        return ExitPath.BREAKOUT;
    }

    @Override
    public ExitPlaybookDecision evaluate(TradingDecisionContext decision,
                                         FuturesPositionDTO position,
                                         ExitPlan plan,
                                         LocalDateTime now) {
        if (decision == null || position == null || plan == null || now == null) {
            return ExitPlaybookDecision.holdBlocked("BREAKOUT数据缺失");
        }
        MarketContext ctx = decision.market();
        if (ctx == null || ctx.price == null || plan.riskPerUnit() == null || plan.riskPerUnit().signum() <= 0) {
            return ExitPlaybookDecision.holdBlocked("BREAKOUT行情/R缺失");
        }

        boolean isLong = isLong(plan, position);
        BigDecimal profitPerUnit = isLong
                ? ctx.price.subtract(plan.entryPrice())
                : plan.entryPrice().subtract(ctx.price);
        BigDecimal profitR = profitPerUnit.divide(plan.riskPerUnit(), 6, RoundingMode.HALF_UP);
        plan.recordHighestProfitR(profitR.doubleValue());
        long holdMinutes = holdMinutes(plan, position, now);

        if (!plan.breakevenDone() && profitR.compareTo(BREAKEVEN_R) >= 0) {
            BigDecimal stop = isLong
                    ? plan.entryPrice().add(plan.riskPerUnit().multiply(BREAKEVEN_LOCK_R))
                    : plan.entryPrice().subtract(plan.riskPerUnit().multiply(BREAKEVEN_LOCK_R));
            if (improvesStop(getCurrentStopLossPrice(position), stop, isLong)) {
                return ExitPlaybookDecision.moveStop(stop, true, "BREAKOUT_BREAKEVEN_1R");
            }
            return ExitPlaybookDecision.hold("BREAKOUT_BREAKEVEN_ALREADY_PROTECTED");
        }

        // 保本前先验证突破是否还有效，避免失败突破拖成被动硬止损。
        if (!plan.breakevenDone()) {
            ExitPlaybookDecision invalid = evaluateEarlyInvalidation(
                    ctx, plan, profitR, holdMinutes, isLong, decision.indicatorExitShielded());
            if (invalid.actionable()) {
                return invalid;
            }
        }

        if (!decision.indicatorExitShielded()
                && plan.breakevenDone()
                && structureReversedAfterBreakeven(ctx, isLong)) {
            return ExitPlaybookDecision.closeFull("BREAKOUT_STRUCTURE_REVERSAL_AFTER_BE");
        }

        if (profitR.compareTo(PARTIAL_R) >= 0 && !plan.isPartialDoneAtR(PARTIAL_R_MILESTONE)) {
            BigDecimal closeQty = position.getQuantity() != null
                    ? position.getQuantity().multiply(PARTIAL_CLOSE_RATIO).setScale(8, RoundingMode.HALF_DOWN)
                    : BigDecimal.ZERO;
            if (closeQty.signum() > 0) {
                return ExitPlaybookDecision.closePartial(closeQty, PARTIAL_R_MILESTONE, "BREAKOUT_PARTIAL_2R");
            }
        }

        // 已经走出至少2R后，用入场ATR做吊灯止损，尽量保留继续奔跑的空间。
        if (plan.highestProfitR() >= PARTIAL_R.doubleValue()) {
            BigDecimal trail = chandelierStop(plan, isLong);
            if (improvesStop(getCurrentStopLossPrice(position), trail, isLong)) {
                return ExitPlaybookDecision.moveStop(trail, "BREAKOUT_CHANDELIER_TRAIL");
            }
        }

        return ExitPlaybookDecision.hold("BREAKOUT_HOLD");
    }

    private ExitPlaybookDecision evaluateEarlyInvalidation(MarketContext ctx, ExitPlan plan,
                                                           BigDecimal profitR, long holdMinutes,
                                                           boolean isLong, boolean indicatorShielded) {
        if (!indicatorShielded && ctx.bollPb5m != null) {
            if (isLong && ctx.bollPb5m < 60.0) {
                return ExitPlaybookDecision.closeFull("BREAKOUT_BB_RETURN_NEUTRAL");
            }
            if (!isLong && ctx.bollPb5m > 40.0) {
                return ExitPlaybookDecision.closeFull("BREAKOUT_BB_RETURN_NEUTRAL");
            }
        }
        if (!plan.recovered()
                && holdMinutes >= FAIL_TO_PROGRESS_MINUTES
                && plan.highestProfitR() < FAIL_TO_PROGRESS_R.doubleValue()) {
            return ExitPlaybookDecision.closeFull("BREAKOUT_NO_0_5R_IN_15M");
        }
        if (!indicatorShielded && volumeExhausted(ctx, plan, holdMinutes)) {
            return ExitPlaybookDecision.closeFull("BREAKOUT_VOLUME_EXHAUSTION");
        }
        return ExitPlaybookDecision.hold("BREAKOUT_EARLY_VALID");
    }

    private boolean volumeExhausted(MarketContext ctx, ExitPlan plan, long holdMinutes) {
        if (plan.recovered() || holdMinutes < FAIL_TO_PROGRESS_MINUTES) {
            return false;
        }
        if (ctx.volumeRatioClosedSeries5m.size() < VOLUME_EXHAUSTION_MIN_BARS) {
            return false;
        }
        int start = ctx.volumeRatioClosedSeries5m.size() - VOLUME_EXHAUSTION_MIN_BARS;
        for (int i = start; i < ctx.volumeRatioClosedSeries5m.size(); i++) {
            Double ratio = ctx.volumeRatioClosedSeries5m.get(i);
            if (ratio == null || ratio >= VOLUME_EXHAUSTION_RATIO) {
                return false;
            }
        }
        return true;
    }

    private boolean structureReversedAfterBreakeven(MarketContext ctx, boolean isLong) {
        boolean ma1hReverse = ctx.maAlignment1h != null
                && (isLong ? ctx.maAlignment1h < 0 : ctx.maAlignment1h > 0);
        boolean ema20Reverse = ctx.ema20 != null
                && (isLong ? ctx.price.compareTo(ctx.ema20) < 0 : ctx.price.compareTo(ctx.ema20) > 0);
        return ma1hReverse && ema20Reverse;
    }

    private BigDecimal chandelierStop(ExitPlan plan, boolean isLong) {
        if (plan.atrAtEntry() == null || plan.atrAtEntry().signum() <= 0) {
            return null;
        }
        BigDecimal bestMove = plan.riskPerUnit().multiply(BigDecimal.valueOf(plan.highestProfitR()));
        BigDecimal bestPrice = isLong ? plan.entryPrice().add(bestMove) : plan.entryPrice().subtract(bestMove);
        BigDecimal gap = plan.atrAtEntry().multiply(CHANDELIER_ATR_MULT);
        return isLong ? bestPrice.subtract(gap) : bestPrice.add(gap);
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
}
