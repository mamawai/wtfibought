package com.mawai.wiibservice.agent.trading.entry.strategy;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyResult;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyCandidate;

import com.mawai.wiibservice.agent.trading.MarketContext;
import com.mawai.wiibservice.agent.trading.SymbolProfile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.mawai.wiibservice.agent.trading.TradingDecisionSupport.PATH_LEGACY_TREND;

public final class TrendContinuationEntryStrategy implements EntryStrategy {

    private static final double MIN_CONFIDENCE = 0.45;
    private static final int MAX_LEVERAGE = 25;
    private static final double MIN_SCORE = 4.2;
    private static final double RSI_LONG_MAX = 78.0;
    private static final double RSI_SHORT_MIN = 22.0;

    @Override
    public EntryStrategyResult build(EntryStrategyContext input) {
        MarketContext ctx = input.market();
        boolean isLong = input.isLong();

        if (input.confidence() < MIN_CONFIDENCE) {
            return EntryStrategyResult.reject(PATH_LEGACY_TREND,
                    "conf=" + fmt(input.confidence()) + "<" + MIN_CONFIDENCE);
        }
        if ("SHOCK".equals(ctx.regime) && input.confidence() < 0.70) {
            return EntryStrategyResult.reject(PATH_LEGACY_TREND, "SHOCK下趋势信号不够强");
        }
        if (ctx.rsi5m != null) {
            double rsi = ctx.rsi5m.doubleValue();
            if (isLong && rsi >= RSI_LONG_MAX) {
                return EntryStrategyResult.reject(PATH_LEGACY_TREND, "做多RSI过热=" + fmt(rsi));
            }
            if (!isLong && rsi <= RSI_SHORT_MIN) {
                return EntryStrategyResult.reject(PATH_LEGACY_TREND, "做空RSI过冷=" + fmt(rsi));
            }
        }

        if (EntryStrategySupport.isDirectionalRegime(ctx.regime) && !EntryStrategySupport.regimeSupports(ctx, isLong)) {
            return EntryStrategyResult.reject(PATH_LEGACY_TREND, "regime=" + ctx.regime + "与方向相反");
        }
        boolean hardTrendAgainst = EntryStrategySupport.directionConflicts(ctx.maAlignment1h, isLong)
                && EntryStrategySupport.directionConflicts(ctx.maAlignment15m, isLong)
                && EntryStrategySupport.isMacdHistAgainst(ctx.macdHistTrend5m, isLong);
        if (hardTrendAgainst) {
            return EntryStrategyResult.reject(PATH_LEGACY_TREND, "1h/15m/MACD强反向");
        }

        double score = input.confidence() * 3.0;
        List<String> parts = new ArrayList<>();
        if (EntryStrategySupport.regimeSupports(ctx, isLong)) {
            score += 1.2;
            parts.add("regime同向");
        }
        if (EntryStrategySupport.directionAligns(ctx.maAlignment1h, isLong)) {
            score += 1.2;
            parts.add("1h同向");
        } else if (EntryStrategySupport.directionConflicts(ctx.maAlignment1h, isLong)) {
            score -= 0.5;
        }
        if (EntryStrategySupport.directionAligns(ctx.maAlignment15m, isLong)) {
            score += 1.0;
            parts.add("15m同向");
        } else if (EntryStrategySupport.directionConflicts(ctx.maAlignment15m, isLong)) {
            score -= 0.3;
        }
        if (EntryStrategySupport.macdSupports(ctx, isLong)) {
            score += 1.0;
            parts.add("MACD同向");
        }
        if (EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong)) {
            score += 0.7;
            parts.add("收盘趋势同向");
        }
        if (EntryStrategySupport.priceAboveEmaSupports(ctx, isLong)) {
            score += 0.6;
            parts.add("EMA20同侧");
        }
        if (ctx.volumeRatio5m != null && ctx.volumeRatio5m >= 1.2) {
            score += 0.4;
            parts.add("量能可用");
        }
        if (EntryStrategySupport.microSupports(ctx, isLong)) {
            score += 0.4;
            parts.add("微结构同向");
        }
        if (isRsiTrendHealthy(ctx, isLong)) {
            score += 0.3;
            parts.add("RSI健康");
        }
        boolean hasTrendBackbone = EntryStrategySupport.regimeSupports(ctx, isLong)
                || EntryStrategySupport.directionAligns(ctx.maAlignment1h, isLong)
                || EntryStrategySupport.directionAligns(ctx.maAlignment15m, isLong)
                || EntryStrategySupport.macdSupports(ctx, isLong)
                || EntryStrategySupport.closeTrendSupports(ctx.closeTrend5m, isLong);
        if (!hasTrendBackbone) {
            return EntryStrategyResult.reject(PATH_LEGACY_TREND, "趋势和动能证据不足");
        }
        if (score < MIN_SCORE) {
            return EntryStrategyResult.reject(PATH_LEGACY_TREND, "score=" + fmt(score) + "<" + MIN_SCORE);
        }

        SymbolProfile profile = input.profile();
        BigDecimal slDistance = ctx.atr5m.multiply(BigDecimal.valueOf(profile.trendSlAtr()));
        BigDecimal tpDistance = ctx.atr5m.multiply(BigDecimal.valueOf(profile.trendTpAtr()));
        return EntryStrategyResult.accept(new EntryStrategyCandidate(
                PATH_LEGACY_TREND, "趋势延续", input.side(), isLong, score, slDistance, tpDistance,
                MAX_LEVERAGE, 1.0,
                "趋势延续[" + input.side() + "] conf=" + fmt(input.confidence()) + " score=" + fmt(score)
                        + " " + String.join("/", parts)));
    }

    private String fmt(double value) {
        return String.format("%.2f", value);
    }

    private boolean isRsiTrendHealthy(MarketContext ctx, boolean isLong) {
        if (ctx.rsi5m == null) return false;
        double rsi = ctx.rsi5m.doubleValue();
        return isLong ? rsi >= 42.0 && rsi < 78.0 : rsi > 22.0 && rsi <= 58.0;
    }
}
