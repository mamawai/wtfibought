package com.mawai.wiibservice.agent.trading;

import java.math.BigDecimal;

import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_BREAKOUT;

final class BreakoutEntryStrategy implements EntryStrategy {

    static final double STRONG_FLAT_SCORE = 6.0;

    private static final int MAX_LEVERAGE = 20;
    private static final double VOLUME_MIN = 1.15;
    private static final double VOLUME_STRONG_MIN = 1.25;
    private static final double POSITION_SCALE = 0.75;
    private static final double MIN_SCORE = 4.8;
    private static final double BB_PB_LONG_MIN = 88.0;
    private static final double BB_PB_SHORT_MAX = 12.0;

    @Override
    public EntryStrategyResult build(EntryStrategyContext input) {
        MarketContext ctx = input.market();
        boolean isLong = input.isLong();

        if (ctx.volumeRatio5m == null || ctx.volumeRatio5m < VOLUME_MIN) {
            return EntryStrategyResult.reject(PATH_BREAKOUT,
                    String.format("成交量不足 %.1fx<%.1fx", ctx.volumeRatio5m != null ? ctx.volumeRatio5m : 0, VOLUME_MIN));
        }
        if (ctx.bollPb5m == null) {
            return EntryStrategyResult.reject(PATH_BREAKOUT, "缺BB%B");
        }
        if (isLong && ctx.bollPb5m < BB_PB_LONG_MIN) {
            return EntryStrategyResult.reject(PATH_BREAKOUT,
                    String.format("做多BB%%B=%.2f<%.0f，未接近上轨突破", ctx.bollPb5m, BB_PB_LONG_MIN));
        }
        if (!isLong && ctx.bollPb5m > BB_PB_SHORT_MAX) {
            return EntryStrategyResult.reject(PATH_BREAKOUT,
                    String.format("做空BB%%B=%.2f>%.0f，未接近下轨突破", ctx.bollPb5m, BB_PB_SHORT_MAX));
        }

        boolean directionalMomentum = EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong)
                || EntryStrategySupport.macdSupports(ctx, isLong);
        if (!ctx.bollSqueeze && !directionalMomentum) {
            return EntryStrategyResult.reject(PATH_BREAKOUT,
                    "突破动能不足 closeTrend=" + ctx.closeTrend5m + " macd=" + ctx.macdHistTrend5m);
        }
        if (EntryStrategySupport.microAgainst(ctx, isLong, 0.35)) {
            return EntryStrategyResult.reject(PATH_BREAKOUT, "微结构明显反向");
        }

        double score = input.confidence() * 2.6;
        score += ctx.bollSqueeze ? 0.8 : 0.4;
        score += ctx.volumeRatio5m >= VOLUME_STRONG_MIN
                ? Math.min(1.5, (ctx.volumeRatio5m - VOLUME_STRONG_MIN) / 0.5 + 0.7)
                : 0.3;
        score += Math.min(1.2, Math.abs(ctx.bollPb5m - 50.0) / 50.0);
        if (EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong)) score += 0.8;
        if (EntryStrategySupport.macdSupports(ctx, isLong)) score += 0.8;
        if (EntryStrategySupport.directionAligns(ctx.maAlignment15m, isLong)) score += 0.5;
        if (EntryStrategySupport.priceAboveEmaSupports(ctx, isLong)) score += 0.4;
        if (EntryStrategySupport.microSupports(ctx, isLong)) score += 0.4;
        if (score < MIN_SCORE) {
            return EntryStrategyResult.reject(PATH_BREAKOUT, "score=" + fmt(score) + "<" + MIN_SCORE);
        }

        SymbolProfile profile = input.profile();
        BigDecimal slDistance = ctx.atr5m.multiply(BigDecimal.valueOf(profile.breakoutSlAtr()));
        BigDecimal tpDistance = ctx.atr5m.multiply(BigDecimal.valueOf(profile.breakoutTpAtr()));
        return EntryStrategyResult.accept(new EntryStrategyCandidate(
                PATH_BREAKOUT, "压缩/释放突破", input.side(), isLong, score, slDistance, tpDistance,
                MAX_LEVERAGE, POSITION_SCALE,
                String.format("压缩/释放突破[%s] conf=%.2f score=%.2f BB%%B=%.2f vol=%.1fx",
                        input.side(), input.confidence(), score, ctx.bollPb5m, ctx.volumeRatio5m)));
    }

    private String fmt(double value) {
        return String.format("%.2f", value);
    }
}
