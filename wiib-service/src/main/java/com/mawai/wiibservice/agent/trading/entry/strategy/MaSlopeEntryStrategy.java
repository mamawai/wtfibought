package com.mawai.wiibservice.agent.trading.entry.strategy;

import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyCandidate;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyResult;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier;
import com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MaState;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MA25_SLOPE_MIN_ATR;
import static com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MIN_SERIES_SIZE;
import static com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MA_SLOPE;

/**
 * MA7/MA25 斜率趋势策略。
 * 核心原则：只做顺势延续；MA7 斜率走平或掉头只负责熄火，不反向抄底/摸顶。
 */
public final class MaSlopeEntryStrategy implements EntryStrategy {

    private static final Set<String> DEFAULT_ENABLED_SYMBOLS = Set.of("BTCUSDT", "ETHUSDT");
    private static volatile Set<String> enabledSymbols = DEFAULT_ENABLED_SYMBOLS;

    private static final int MAX_LEVERAGE = 10;
    private static final double PRICE_DISTANCE_SOFT_ATR = 3.50;
    private static final double PRICE_DISTANCE_HARD_ATR = 5.00;
    private static final double FUNDING_EXTREME = 0.60;
    private static final double SCORE_MIN = 0.45;
    private static final double SCORE_MAX = 1.20;

    public static void setEnabledSymbols(Collection<String> symbols) {
        if (symbols == null) {
            enabledSymbols = DEFAULT_ENABLED_SYMBOLS;
            return;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String symbol : symbols) {
            if (symbol != null && !symbol.isBlank()) {
                normalized.add(symbol.trim().toUpperCase(Locale.ROOT));
            }
        }
        enabledSymbols = Set.copyOf(normalized);
    }

    public static Set<String> enabledSymbols() {
        return enabledSymbols;
    }

    @Override
    public StrategyKind kind() {
        return StrategyKind.DIRECTION_AUTONOMOUS;
    }

    @Override
    public String path() {
        return PATH_MA_SLOPE;
    }

    @Override
    public EntryStrategyResult build(EntryStrategyContext input) {
        MarketContext ctx = input.market();
        KlineInterval interval = input.decisionInterval() != null ? input.decisionInterval() : ctx.decisionInterval;
        String symbol = input.symbol() != null ? input.symbol().trim().toUpperCase(Locale.ROOT) : "";

        if (symbol.isBlank()) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE, "MASLOPE_SYMBOL_MISSING");
        }
        if (!enabledSymbols.contains(symbol)) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE, "MASLOPE_SYMBOL_NOT_ENABLED symbol=" + symbol);
        }
        if (!isSupportedTf(interval)) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE, "MASLOPE_UNSUPPORTED_TF tf=" + interval);
        }
        if (!hasMaSlopeData(ctx)) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE, "MASLOPE_DATA_MISSING");
        }

        MaState trigger = MaSlopeStateClassifier.classifyPrimary(ctx);
        if (!trigger.hasCandidate()) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE,
                    "MASLOPE_NO_STRONG_STATE state=" + trigger.state());
        }

        boolean isLong = trigger.isLong();
        MaState prevTrigger = MaSlopeStateClassifier.classifyPreviousPrimary(ctx);
        if (!prevTrigger.sameStrongDirection(isLong)) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE,
                    "MASLOPE_NEED_2BAR_CONFIRM prev=" + prevTrigger.state()
                            + " curr=" + trigger.state());
        }

        String side = isLong ? "LONG" : "SHORT";
        MaState confirm = MaSlopeStateClassifier.classifyConfirm(ctx);
        if (confirm.stronglyAgainst(isLong)) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE,
                    "MASLOPE_CONFIRM_AGAINST state=" + trigger.state() + " confirm=" + confirm.state());
        }

        String hardReject = hardGate(ctx, trigger, interval, isLong);
        if (hardReject != null) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE, hardReject);
        }

        double score = score(ctx, trigger, confirm, isLong);
        double positionScale = positionScale(trigger, confirm);
        if (trigger.priceDistanceAtr() > PRICE_DISTANCE_SOFT_ATR) {
            positionScale *= 0.80;
        }

        SymbolProfile profile = input.profile();
        BigDecimal slDistance = ctx.atrClosed.multiply(BigDecimal.valueOf(profile.trendSlAtr()));
        BigDecimal tpDistance = ctx.atrClosed.multiply(BigDecimal.valueOf(profile.trendTpAtr()));

        return EntryStrategyResult.accept(new EntryStrategyCandidate(
                PATH_MA_SLOPE,
                "均线斜率趋势",
                side,
                isLong,
                score,
                slDistance,
                tpDistance,
                MAX_LEVERAGE,
                positionScale,
                reason(trigger, confirm, ctx, score)));
    }

    private static boolean hasMaSlopeData(MarketContext ctx) {
        return ctx != null
                && !ctx.atrFromFallback
                && isPositive(ctx.atr)
                && isPositive(ctx.atrClosed)
                && ctx.price != null && ctx.price.signum() > 0
                && ctx.ma7SeriesClosed.size() >= MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM
                && ctx.ma25SeriesClosed.size() >= MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM
                && ctx.atrSeriesClosed.size() >= 2
                && isPositive(ctx.confirmAtr)
                && ctx.confirmMa7SeriesClosed.size() >= MIN_SERIES_SIZE
                && ctx.confirmMa25SeriesClosed.size() >= MIN_SERIES_SIZE
                && finite(ctx.adx)
                && ctx.atrMean30Closed != null && ctx.atrMean30Closed.signum() > 0
                && finite(ctx.atrSpikeRatio);
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static boolean isSupportedTf(KlineInterval interval) {
        return interval == KlineInterval.M3 || interval == KlineInterval.M5 || interval == KlineInterval.M15;
    }

    private static String hardGate(MarketContext ctx, MaState trigger, KlineInterval interval, boolean isLong) {
        if (ctx.qualityFlags.contains("STALE_AGG_TRADE")) {
            return "MASLOPE_STALE_AGG_TRADE";
        }
        if ("SHOCK".equals(ctx.regime)) {
            return "MASLOPE_SHOCK_REGIME";
        }
        double adxThreshold = adxRejectThreshold(interval);
        if (ctx.adx < adxThreshold) {
            return "MASLOPE_ADX_WEAK adx=" + fmt(ctx.adx) + "<" + fmt(adxThreshold);
        }
        if (isLong && trigger.ma25SlopeAtr() < MA25_SLOPE_MIN_ATR) {
            return "MASLOPE_MA25_FLAT_OR_AGAINST";
        }
        if (!isLong && trigger.ma25SlopeAtr() > -MA25_SLOPE_MIN_ATR) {
            return "MASLOPE_MA25_FLAT_OR_AGAINST";
        }
        if (trigger.priceDistanceAtr() > PRICE_DISTANCE_HARD_ATR) {
            return "MASLOPE_TOO_EXTENDED distanceAtr=" + fmt(trigger.priceDistanceAtr());
        }
        double atrSpikeThreshold = atrSpikeRejectThreshold(interval);
        if (ctx.atrSpikeRatio > atrSpikeThreshold) {
            return "MASLOPE_ATR_SPIKE ratio=" + fmt(ctx.atrSpikeRatio) + ">" + fmt(atrSpikeThreshold);
        }
        if (EntryStrategySupport.directionConflicts(ctx.maAlignment1h, isLong) && macdAgainst(ctx, isLong)) {
            return "MASLOPE_MAJOR_CONFLICT";
        }
        if (EntryStrategySupport.microAgainst(ctx, isLong, 0.35)) {
            return "MASLOPE_MICRO_AGAINST";
        }
        return null;
    }

    private static double score(MarketContext ctx, MaState trigger, MaState confirm, boolean isLong) {
        double score = trigger.baseConfidence();
        if (confirm.sameStrongDirection(isLong)) score *= 1.12;
        if (EntryStrategySupport.regimeSupports(ctx, isLong)) score *= 1.10;
        if (EntryStrategySupport.directionAligns(ctx.maAlignment1h, isLong)) score *= 1.08;
        if (ctx.decisionInterval != KlineInterval.M15
                && EntryStrategySupport.directionAligns(ctx.maAlignment15m, isLong)) score *= 1.06;
        if (EntryStrategySupport.macdSupports(ctx, isLong)) score *= 1.08;
        if (EntryStrategySupport.closeTrendSupports(ctx.closeTrend, isLong)) score *= 1.04;
        if (EntryStrategySupport.priceAboveEmaSupports(ctx, isLong)) score *= 1.03;

        double avgClosedVol = avg(ctx.volumeRatioClosedSeries);
        if (avgClosedVol >= 1.50) {
            score *= 1.08;
        } else if (avgClosedVol >= 1.20) {
            score *= 1.04;
        }

        if (ctx.bollExpanding5) score *= 1.05;
        if (EntryStrategySupport.microSupports(ctx, isLong)) score *= 1.03;
        if (trigger.priceDistanceAtr() > PRICE_DISTANCE_SOFT_ATR) score *= 0.85;
        if (isFundingCrowded(ctx.fundingRateExtreme, isLong)) score *= 0.92;
        return Math.clamp(score, SCORE_MIN, SCORE_MAX);
    }

    private static double positionScale(MaState trigger, MaState confirm) {
        return trigger.isAccelerating() && confirm.sameStrongDirection(trigger.isLong()) ? 0.75 : 0.65;
    }

    private static boolean macdAgainst(MarketContext ctx, boolean isLong) {
        if ("death".equals(ctx.macdCross)) return isLong;
        if ("golden".equals(ctx.macdCross)) return !isLong;
        if (ctx.macdDif != null && ctx.macdDea != null) {
            int cmp = ctx.macdDif.compareTo(ctx.macdDea);
            if (isLong && cmp < 0) return true;
            if (!isLong && cmp > 0) return true;
        }
        return EntryStrategySupport.isMacdHistAgainst(ctx.macdHistTrend, isLong);
    }

    private static boolean isFundingCrowded(Double fundingRateExtreme, boolean isLong) {
        if (fundingRateExtreme == null) {
            return false;
        }
        return isLong ? fundingRateExtreme > FUNDING_EXTREME : fundingRateExtreme < -FUNDING_EXTREME;
    }

    private static double avg(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        return values.stream().filter(v -> v != null && Double.isFinite(v))
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double adxRejectThreshold(KlineInterval interval) {
        return switch (interval) {
            case M3 -> 22.0;
            case M15 -> 18.0;
            default -> 20.0;
        };
    }

    private static double atrSpikeRejectThreshold(KlineInterval interval) {
        return switch (interval) {
            case M3 -> 2.0;
            case M15 -> 1.5;
            default -> 1.8;
        };
    }

    private static String reason(MaState trigger, MaState confirm, MarketContext ctx, double score) {
        return String.format(Locale.US,
                "均线斜率趋势[%s] state=%s confirm=%s ma7SlopeAtr=%.2f ma7PrevSlopeAtr=%.2f "
                        + "ma25SlopeAtr=%.2f spreadAtr=%.2f spreadDeltaAtr=%.2f adx=%.2f "
                        + "priceDistanceAtr=%.2f atrSpikeRatio=%.2f score=%.2f",
                trigger.isLong() ? "LONG" : "SHORT",
                trigger.state(), confirm.state(),
                trigger.ma7SlopeAtr(), trigger.ma7PrevSlopeAtr(),
                trigger.ma25SlopeAtr(), trigger.spreadAtr(), trigger.spreadDeltaAtr(),
                ctx.adx != null ? ctx.adx : 0.0,
                trigger.priceDistanceAtr(),
                ctx.atrSpikeRatio != null ? ctx.atrSpikeRatio : 0.0,
                score);
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }
}
