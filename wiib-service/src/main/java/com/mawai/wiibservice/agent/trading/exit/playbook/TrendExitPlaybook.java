package com.mawai.wiibservice.agent.trading.exit.playbook;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPath;
import com.mawai.wiibservice.agent.trading.exit.model.ExitPlan;
import com.mawai.wiibservice.agent.trading.MarketContext;
import com.mawai.wiibservice.agent.trading.TradingDecisionContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.getCurrentStopLossPrice;

/**
 * 趋势仓位的退出剧本。
 *
 * <p>趋势单允许正常回踩：保本前只处理明确失效；保本后用确认后的高周期反转、
 * +3R 部分止盈、ATR trail 和 90 分钟无进展退出管理剩余仓位。</p>
 */
final class TrendExitPlaybook implements ExitPlaybook {

    private static final BigDecimal BREAKEVEN_R = BigDecimal.ONE;
    private static final BigDecimal TRAIL_R = new BigDecimal("2.00");
    private static final BigDecimal PARTIAL_R = new BigDecimal("3.00");
    private static final int PARTIAL_R_MILESTONE = 300;
    private static final BigDecimal PARTIAL_CLOSE_RATIO = new BigDecimal("0.30");
    private static final BigDecimal TIME_PROGRESS_R = new BigDecimal("0.50");
    private static final long TIME_LIMIT_MINUTES = 90;
    private static final BigDecimal ATR_TRAIL_MULT = new BigDecimal("2.0");

    @Override
    public ExitPath path() {
        return ExitPath.TREND;
    }

    @Override
    public ExitPlaybookDecision evaluate(TradingDecisionContext decision,
                                         FuturesPositionDTO position,
                                         ExitPlan plan,
                                         LocalDateTime now) {
        if (decision == null || position == null || plan == null || now == null) {
            return ExitPlaybookDecision.holdBlocked("TREND数据缺失");
        }
        MarketContext ctx = decision.market();
        if (ctx == null || ctx.price == null || plan.riskPerUnit() == null || plan.riskPerUnit().signum() <= 0) {
            return ExitPlaybookDecision.holdBlocked("TREND行情/R缺失");
        }

        boolean isLong = isLong(plan, position);
        BigDecimal profitPerUnit = isLong
                ? ctx.price.subtract(plan.entryPrice())
                : plan.entryPrice().subtract(ctx.price);
        BigDecimal profitR = profitPerUnit.divide(plan.riskPerUnit(), 6, RoundingMode.HALF_UP);
        plan.recordHighestProfitR(profitR.doubleValue());
        long holdMinutes = holdMinutes(plan, position, now);

        if (!plan.breakevenDone() && profitR.compareTo(BREAKEVEN_R) >= 0) {
            BigDecimal stop = plan.entryPrice();
            if (improvesStop(getCurrentStopLossPrice(position), stop, isLong)) {
                return ExitPlaybookDecision.moveStop(stop, true, "TREND_BREAKEVEN_1R");
            }
            return ExitPlaybookDecision.hold("TREND_BREAKEVEN_ALREADY_PROTECTED");
        }

        // 保本前只认明确结构坏掉；指标护盾打开时不做指标类失效判断。
        if (!decision.indicatorExitShielded() && !plan.breakevenDone()) {
            ExitPlaybookDecision invalid = evaluateEarlyInvalidation(ctx, isLong);
            if (invalid.actionable()) {
                return invalid;
            }
        }

        if (!decision.indicatorExitShielded() && plan.breakevenDone()
                && confirmedHigherTimeframeReversal(ctx, isLong)) {
            return ExitPlaybookDecision.closeFull("TREND_1H_CONFIRM_REVERSE_AFTER_BE");
        }

        // +3R 必须抢在 trail 之前评估：trail 在持续上涨行情里每 tick 都能 improve，会把 partial 永远卡在
        // 后面。命中 +3R 时同 cycle 平 30% 并把剩余 70% 仓位 SL 同步抬到 ATR trail，下一轮再继续 trail。
        if (profitR.compareTo(PARTIAL_R) >= 0 && !plan.isPartialDoneAtR(PARTIAL_R_MILESTONE)) {
            BigDecimal closeQty = position.getQuantity() != null
                    ? position.getQuantity().multiply(PARTIAL_CLOSE_RATIO).setScale(8, RoundingMode.HALF_DOWN)
                    : BigDecimal.ZERO;
            if (closeQty.signum() > 0) {
                BigDecimal trail = atrTrailStop(ctx, plan, isLong);
                BigDecimal stop = improvesStop(getCurrentStopLossPrice(position), trail, isLong) ? trail : null;
                return ExitPlaybookDecision.closePartialAndMoveStop(
                        closeQty, stop, PARTIAL_R_MILESTONE, "TREND_PARTIAL_3R");
            }
        }

        // +2R 后才启动 ATR trail，避免小盈利阶段被普通波动扫掉。
        if (profitR.compareTo(TRAIL_R) >= 0) {
            BigDecimal trail = atrTrailStop(ctx, plan, isLong);
            if (improvesStop(getCurrentStopLossPrice(position), trail, isLong)) {
                return ExitPlaybookDecision.moveStop(trail, "TREND_ATR_TRAIL_2R");
            }
        }

        if (holdMinutes >= TIME_LIMIT_MINUTES && plan.highestProfitR() < TIME_PROGRESS_R.doubleValue()) {
            return ExitPlaybookDecision.closeFull("TREND_NO_0_5R_IN_90M");
        }

        return ExitPlaybookDecision.hold("TREND_HOLD");
    }

    private ExitPlaybookDecision evaluateEarlyInvalidation(MarketContext ctx, boolean isLong) {
        if (confirmedHigherTimeframeReversal(ctx, isLong)) {
            return ExitPlaybookDecision.closeFull("TREND_1H_CONFIRM_REVERSE_BEFORE_BE");
        }
        if (ema20Reversed(ctx, isLong) && macdReversed(ctx, isLong)) {
            return ExitPlaybookDecision.closeFull("TREND_EMA_MACD_REVERSE_BEFORE_BE");
        }
        return ExitPlaybookDecision.hold("TREND_EARLY_VALID");
    }

    private boolean confirmedHigherTimeframeReversal(MarketContext ctx, boolean isLong) {
        // 1h反向只说明大级别有变化苗头；必须叠加EMA20或MACD确认，避免普通回踩就全平。
        return ma1hReversed(ctx, isLong) && (ema20Reversed(ctx, isLong) || macdReversed(ctx, isLong));
    }

    private boolean ma1hReversed(MarketContext ctx, boolean isLong) {
        return ctx.maAlignment1h != null && (isLong ? ctx.maAlignment1h < 0 : ctx.maAlignment1h > 0);
    }

    private boolean ema20Reversed(MarketContext ctx, boolean isLong) {
        if (ctx.ema20 == null) {
            return false;
        }
        return isLong ? ctx.price.compareTo(ctx.ema20) < 0 : ctx.price.compareTo(ctx.ema20) > 0;
    }

    private boolean macdReversed(MarketContext ctx, boolean isLong) {
        // TREND 只认交叉或 DIF/DEA 已反向；不把柱子短暂回落当结构坏，避免趋势回踩被过早打掉。
        if (ctx.macdCross5m != null) {
            if (isLong && "death".equalsIgnoreCase(ctx.macdCross5m)) {
                return true;
            }
            if (!isLong && "golden".equalsIgnoreCase(ctx.macdCross5m)) {
                return true;
            }
        }
        if (ctx.macdDif5m == null || ctx.macdDea5m == null) {
            return false;
        }
        return isLong
                ? ctx.macdDif5m.compareTo(ctx.macdDea5m) < 0
                : ctx.macdDif5m.compareTo(ctx.macdDea5m) > 0;
    }

    private BigDecimal atrTrailStop(MarketContext ctx, ExitPlan plan, boolean isLong) {
        BigDecimal atr = plan.atrAtEntry();
        if (atr == null || atr.signum() <= 0) {
            atr = ctx.atr5m;
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
}
