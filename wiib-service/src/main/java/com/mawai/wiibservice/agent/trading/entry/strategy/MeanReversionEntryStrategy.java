package com.mawai.wiibservice.agent.trading.entry.strategy;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyResult;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyCandidate;

import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;

import com.mawai.wiibservice.agent.trading.runtime.MarketContext;

import java.math.BigDecimal;

import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MR;

public final class MeanReversionEntryStrategy implements EntryStrategy {

    private static final int MAX_LEVERAGE = 20;
    private static final double POSITION_SCALE = 0.55;
    private static final double MIN_CONFIDENCE = 0.45;
    private static final double MIN_SCORE = 4.0;
    private static final double BB_PB_LONG_MAX = 20.0;
    private static final double BB_PB_SHORT_MIN = 80.0;
    private static final double BB_PB_LONG_STRONG = 15.0;
    private static final double BB_PB_SHORT_STRONG = 85.0;
    private static final double RSI_LONG_MAX = 44.0;
    private static final double RSI_SHORT_MIN = 56.0;
    private static final double RSI_LONG_STRONG = 38.0;
    private static final double RSI_SHORT_STRONG = 62.0;

    @Override
    public EntryStrategyResult build(EntryStrategyContext input) {
        MarketContext ctx = input.market();
        boolean isLong = input.isLong();

        if (input.confidence() < MIN_CONFIDENCE) {
            return EntryStrategyResult.reject(PATH_MR,
                    "conf=" + fmt(input.confidence()) + "<" + MIN_CONFIDENCE);
        }
        if ("SHOCK".equals(ctx.regime)) {
            return EntryStrategyResult.reject(PATH_MR, "SHOCK环境不做均值回归");
        }
        if (ctx.bollPb5m == null) {
            return EntryStrategyResult.reject(PATH_MR, "缺BB%B");
        }
        if (isLong && ctx.bollPb5m > BB_PB_LONG_MAX) {
            return EntryStrategyResult.reject(PATH_MR,
                    String.format("做多BB%%B=%.2f>%.2f", ctx.bollPb5m, BB_PB_LONG_MAX));
        }
        if (!isLong && ctx.bollPb5m < BB_PB_SHORT_MIN) {
            return EntryStrategyResult.reject(PATH_MR,
                    String.format("做空BB%%B=%.2f<%.2f", ctx.bollPb5m, BB_PB_SHORT_MIN));
        }

        boolean rsiConfirms = false;
        if (ctx.rsi5m != null) {
            double rsi = ctx.rsi5m.doubleValue();
            rsiConfirms = (isLong && rsi <= RSI_LONG_MAX) || (!isLong && rsi >= RSI_SHORT_MIN);
        }
        if (!rsiConfirms) {
            return EntryStrategyResult.reject(PATH_MR,
                    "RSI未拉伸=" + (ctx.rsi5m != null ? fmt(ctx.rsi5m.doubleValue()) : "无"));
        }
        boolean deepStretch = isDeepStretch(ctx, isLong);
        int reversalVotes = reversalVoteCount(ctx, isLong);
        if (deepStretch && reversalVotes < 1) {
            return EntryStrategyResult.reject(PATH_MR,
                    "深度偏离但无反转确认 RSI=" + fmt(ctx.rsi5m.doubleValue()) + " MACD=" + ctx.macdHistTrend5m);
        }
        if (!deepStretch && reversalVotes < 2) {
            return EntryStrategyResult.reject(PATH_MR,
                    "反转确认不足 votes=" + reversalVotes + " RSI=" + fmt(ctx.rsi5m.doubleValue())
                            + " MACD=" + ctx.macdHistTrend5m);
        }

        boolean hardTrendAgainst = EntryStrategySupport.directionConflicts(ctx.maAlignment1h, isLong)
                && EntryStrategySupport.directionConflicts(ctx.maAlignment15m, isLong)
                && EntryStrategySupport.isMacdHistAgainst(ctx.macdHistTrend5m, isLong);
        if (hardTrendAgainst && (!"WEAKENING".equals(ctx.regimeTransition) || !deepStretch)) {
            return EntryStrategyResult.reject(PATH_MR, "1h/15m强趋势仍在推进，禁止逆势MR");
        }

        double score = getScore(input, isLong, ctx);
        if (score < MIN_SCORE) {
            return EntryStrategyResult.reject(PATH_MR, "score=" + fmt(score) + "<" + MIN_SCORE);
        }

        SymbolProfile profile = input.profile();
        double tpAtrMult = getTpAtrMult(profile, ctx);

        double scale = POSITION_SCALE;
        if (EntryStrategySupport.directionConflicts(ctx.maAlignment1h, isLong)) scale *= 0.70;
        BigDecimal slDistance = ctx.atr5m.multiply(BigDecimal.valueOf(profile.revertSlAtr()));
        BigDecimal tpDistance = ctx.atr5m.multiply(BigDecimal.valueOf(tpAtrMult));
        return EntryStrategyResult.accept(new EntryStrategyCandidate(
                PATH_MR, "均值回归", input.side(), isLong, score, slDistance, tpDistance,
                MAX_LEVERAGE, scale,
                String.format("均值回归[%s] conf=%.2f score=%.2f RSI=%.1f BB%%B=%.2f",
                        input.side(), input.confidence(), score,
                        ctx.rsi5m.doubleValue(),
                        ctx.bollPb5m)));
    }

    private static double getTpAtrMult(SymbolProfile profile, MarketContext ctx) {
        double tpAtrMult = profile.revertTpMaxAtr();
        if (ctx.bollBandwidth5m != null && ctx.bollBandwidth5m > 0 && ctx.atr5m.signum() > 0) {
            double distToMidPct = Math.abs(ctx.bollPb5m - 50.0) / 100.0;
            double bbWidthAbsolute = ctx.bollBandwidth5m / 100.0 * ctx.price.doubleValue();
            double distToMidAbsolute = distToMidPct * bbWidthAbsolute;
            tpAtrMult = distToMidAbsolute / ctx.atr5m.doubleValue();
            tpAtrMult = Math.clamp(tpAtrMult, profile.revertTpMinAtr(), profile.revertTpMaxAtr());
        }
        return tpAtrMult;
    }

    private static double getScore(EntryStrategyContext input, boolean isLong, MarketContext ctx) {
        double score = input.confidence() * 3.0;
        // 按第一层阈值到深度阈值归一化，避免收紧门槛后分数整体被压死。
        double pbExtreme = isLong
                ? (BB_PB_LONG_MAX - ctx.bollPb5m) / (BB_PB_LONG_MAX - BB_PB_LONG_STRONG)
                : (ctx.bollPb5m - BB_PB_SHORT_MIN) / (BB_PB_SHORT_STRONG - BB_PB_SHORT_MIN);
        score += Math.clamp(pbExtreme, 0.0, 1.0) * 1.5;
        double rsiStretch = isLong
                ? (RSI_LONG_MAX - ctx.rsi5m.doubleValue()) / (RSI_LONG_MAX - RSI_LONG_STRONG)
                : (ctx.rsi5m.doubleValue() - RSI_SHORT_MIN) / (RSI_SHORT_STRONG - RSI_SHORT_MIN);
        score += Math.clamp(rsiStretch, 0.0, 1.0);
        score += Math.min(1.2, reversalVoteCount(ctx, isLong) * 0.4);
        score += "RANGE".equals(ctx.regime) ? 1.0 : 0;
        score += "SQUEEZE".equals(ctx.regime) ? 0.5 : 0;
        score += "WEAKENING".equals(ctx.regimeTransition) ? 0.5 : 0;
        if (EntryStrategySupport.microSupports(ctx, isLong)) score += 0.4;
        if (EntryStrategySupport.directionConflicts(ctx.maAlignment1h, isLong)) score -= 0.6;
        return score;
    }

    private static boolean isDeepStretch(MarketContext ctx, boolean isLong) {
        return isLong
                ? ctx.bollPb5m <= BB_PB_LONG_STRONG && ctx.rsi5m.doubleValue() <= RSI_LONG_STRONG
                : ctx.bollPb5m >= BB_PB_SHORT_STRONG && ctx.rsi5m.doubleValue() >= RSI_SHORT_STRONG;
    }

    private static int reversalVoteCount(MarketContext ctx, boolean isLong) {
        int votes = 0;
        if (EntryStrategySupport.macdSupports(ctx, isLong)) votes++;
        if (EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong)) votes++;
        if (EntryStrategySupport.microSupports(ctx, isLong)) votes++;
        if ("WEAKENING".equals(ctx.regimeTransition)) votes++;
        return votes;
    }

    private String fmt(double value) {
        return String.format("%.2f", value);
    }
}
