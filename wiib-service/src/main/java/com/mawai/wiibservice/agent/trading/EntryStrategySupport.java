package com.mawai.wiibservice.agent.trading;

/**
 * 入场策略共享的方向/动能判断。
 */
final class EntryStrategySupport {

    private EntryStrategySupport() {
    }

    static boolean directionAligns(Integer alignment, boolean isLong) {
        if (alignment == null) return false;
        return isLong ? alignment > 0 : alignment < 0;
    }

    static boolean directionAlignsOrFlat(Integer alignment, boolean isLong) {
        if (alignment == null) return false;
        return isLong ? alignment >= 0 : alignment <= 0;
    }

    static boolean directionConflicts(Integer alignment, boolean isLong) {
        if (alignment == null) return false;
        return isLong ? alignment < 0 : alignment > 0;
    }

    static boolean isDirectionalRegime(String regime) {
        return "TREND_UP".equals(regime) || "TREND_DOWN".equals(regime);
    }

    static boolean regimeSupports(MarketContext ctx, boolean isLong) {
        if ("TREND_UP".equals(ctx.regime)) return isLong;
        if ("TREND_DOWN".equals(ctx.regime)) return !isLong;
        return false;
    }

    static boolean macdSupports(MarketContext ctx, boolean isLong) {
        if ("golden".equals(ctx.macdCross5m)) return isLong;
        if ("death".equals(ctx.macdCross5m)) return !isLong;

        boolean histBullish = isMacdHistBullish(ctx.macdHistTrend5m);
        boolean histBearish = isMacdHistBearish(ctx.macdHistTrend5m);

        if (ctx.macdDif5m != null && ctx.macdDea5m != null) {
            int cmp = ctx.macdDif5m.compareTo(ctx.macdDea5m);
            if (isLong && cmp > 0) return !histBearish;
            if (!isLong && cmp < 0) return !histBullish;
        }

        return isLong ? histBullish : histBearish;
    }

    static boolean isMacdHistBullish(String trend) {
        return trend != null && (trend.startsWith("rising") || "mostly_up".equals(trend));
    }

    static boolean isMacdHistBearish(String trend) {
        return trend != null && (trend.startsWith("falling") || "mostly_down".equals(trend));
    }

    static boolean isMacdHistAgainst(String trend, boolean isLong) {
        return isLong ? isMacdHistBearish(trend) : isMacdHistBullish(trend);
    }

    static boolean closeTrendSupports(String trend, boolean isLong) {
        if (trend == null) return false;
        return isLong
                ? (trend.startsWith("rising") || "mostly_up".equals(trend))
                : (trend.startsWith("falling") || "mostly_down".equals(trend));
    }

    static boolean priceAboveEmaSupports(MarketContext ctx, boolean isLong) {
        if (ctx.ema20 == null || ctx.price == null) return false;
        return isLong ? ctx.price.compareTo(ctx.ema20) > 0 : ctx.price.compareTo(ctx.ema20) < 0;
    }

    static boolean microSupports(MarketContext ctx, boolean isLong) {
        double micro = micro(ctx);
        return isLong ? micro > 0.15 : micro < -0.15;
    }

    static boolean microAgainst(MarketContext ctx, boolean isLong, double threshold) {
        double micro = micro(ctx);
        return isLong ? micro < -threshold : micro > threshold;
    }

    private static double micro(MarketContext ctx) {
        double micro = 0;
        if (ctx.bidAskImbalance != null) micro += ctx.bidAskImbalance;
        if (ctx.takerPressure != null) micro += ctx.takerPressure;
        return micro;
    }
}
