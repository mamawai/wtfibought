package com.mawai.wiibservice.agent.trading.maslope;

import com.mawai.wiibservice.agent.trading.entry.strategy.EntryStrategySupport;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MaState;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MA7_SLOPE_MIN_ATR;

/**
 * MaSlope 持仓失败评分。
 * 只用闭合主周期指标；退出和回测报告共用，避免两边诊断口径分叉。
 */
public final class MaSlopeFailureEvaluator {

    private MaSlopeFailureEvaluator() {
    }

    public record FailureScore(int score, List<String> reasons) {
        public String reasonText() {
            return reasons == null || reasons.isEmpty() ? "NONE" : String.join(",", reasons);
        }
    }

    public static FailureScore score(MarketContext ctx,
                                     MaState current,
                                     boolean isLong,
                                     Double entryMa7SlopeAtr) {
        if (ctx == null || current == null) {
            return new FailureScore(0, List.of());
        }

        List<String> reasons = new ArrayList<>();
        if (priceLostMa7(ctx, isLong)) {
            reasons.add(isLong ? "CLOSE_BELOW_MA7" : "CLOSE_ABOVE_MA7");
        }
        if (ma7SlopeWeakerThanEntry(current, isLong, entryMa7SlopeAtr)) {
            reasons.add("MA7_SLOPE_WEAKER");
        }
        if (spreadDeltaWeak(current, isLong)) {
            reasons.add("SPREAD_DELTA_WEAK");
        }
        if (macdWeak(ctx, isLong)) {
            reasons.add("MACD_WEAK");
        }
        if (diFlipped(ctx, isLong)) {
            reasons.add("DI_FLIPPED");
        }
        if (closeTrendAgainst(ctx, isLong)) {
            reasons.add("CLOSE_TREND_AGAINST");
        }
        return new FailureScore(reasons.size(), List.copyOf(reasons));
    }

    private static boolean priceLostMa7(MarketContext ctx, boolean isLong) {
        BigDecimal close = lastClosedClose(ctx);
        BigDecimal ma7 = lastMa7(ctx);
        if (close == null || ma7 == null) {
            return false;
        }
        return isLong ? close.compareTo(ma7) < 0 : close.compareTo(ma7) > 0;
    }

    private static BigDecimal lastClosedClose(MarketContext ctx) {
        if (ctx.closeSeriesClosed != null && !ctx.closeSeriesClosed.isEmpty()) {
            return ctx.closeSeriesClosed.getLast();
        }
        return ctx.price;
    }

    private static BigDecimal lastMa7(MarketContext ctx) {
        if (ctx.ma7SeriesClosed != null && !ctx.ma7SeriesClosed.isEmpty()) {
            return ctx.ma7SeriesClosed.getLast();
        }
        return ctx.ma7;
    }

    private static boolean ma7SlopeWeakerThanEntry(MaState current, boolean isLong, Double entryMa7SlopeAtr) {
        double entrySlope = entryMa7SlopeAtr != null && Double.isFinite(entryMa7SlopeAtr)
                ? entryMa7SlopeAtr
                : (isLong ? MA7_SLOPE_MIN_ATR : -MA7_SLOPE_MIN_ATR);
        return isLong ? current.ma7SlopeAtr() < entrySlope : current.ma7SlopeAtr() > entrySlope;
    }

    private static boolean spreadDeltaWeak(MaState current, boolean isLong) {
        return isLong ? current.spreadDeltaAtr() <= 0.0 : current.spreadDeltaAtr() >= 0.0;
    }

    private static boolean macdWeak(MarketContext ctx, boolean isLong) {
        if ("death".equals(ctx.macdCross)) {
            return isLong;
        }
        if ("golden".equals(ctx.macdCross)) {
            return !isLong;
        }
        if (ctx.macdDif != null && ctx.macdDea != null) {
            int cmp = ctx.macdDif.compareTo(ctx.macdDea);
            if (isLong && cmp < 0) return true;
            if (!isLong && cmp > 0) return true;
        }
        return EntryStrategySupport.isMacdHistAgainst(ctx.macdHistTrend, isLong);
    }

    private static boolean diFlipped(MarketContext ctx, boolean isLong) {
        if (ctx.plusDi == null || ctx.minusDi == null) {
            return false;
        }
        return isLong ? ctx.minusDi > ctx.plusDi : ctx.plusDi > ctx.minusDi;
    }

    private static boolean closeTrendAgainst(MarketContext ctx, boolean isLong) {
        return EntryStrategySupport.closeTrendSupports(ctx.closeTrendClosed3, !isLong);
    }
}
