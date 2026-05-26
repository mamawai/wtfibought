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
import static com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MA7_SLOPE_STRONG_ATR;
import static com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MIN_SERIES_SIZE;
import static com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM;
import static com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.SPREAD_DELTA_MIN_ATR;
import static com.mawai.wiibservice.agent.trading.maslope.MaSlopeStateClassifier.SPREAD_MIN_ATR;
import static com.mawai.wiibservice.agent.trading.runtime.TradingDecisionSupport.PATH_MA_SLOPE;

/**
 * MA7/MA25 斜率趋势策略。
 * 核心原则：只做顺势延续；MA7 斜率走平或掉头只负责熄火，不反向抄底/摸顶。
 */
public final class MaSlopeEntryStrategy implements EntryStrategy {

    private static final Set<String> DEFAULT_ENABLED_SYMBOLS = Set.of("BTCUSDT", "ETHUSDT");
    private static volatile Set<String> enabledSymbols = DEFAULT_ENABLED_SYMBOLS;

    private static final int MAX_LEVERAGE = 50;
    // 60d ETH 回测：MA25 接近 0 的早进信号几乎全亏，容忍度收到 0。
    private static final double MA25_EARLY_LAG_TOLERANCE_ATR = 0.0;
    private static final double PRICE_DISTANCE_LAUNCH_ATR = 2.50;
    private static final double PRICE_DISTANCE_PULLBACK_ATR = 3.50;
    private static final double FUNDING_EXTREME = 0.60;
    private static final double SCORE_MIN = 0.45;
    private static final double SCORE_MAX = 1.20;
    private static final double M3_TREND_CONTINUATION_MIN_VOLUME = 0.75;
    private static final double BTC_M3_TREND_CONTINUATION_MIN_VOLUME = 1.10;
    private static final double M3_TREND_CONTINUATION_MAX_DISTANCE_ATR = 4.50;
    private static final double BTC_M3_TREND_CONTINUATION_MAX_DISTANCE_ATR = 4.00;
    private static final double M3_TREND_CONTINUATION_MAX_RANGE_ATR = 2.20;
    private static final double BTC_M3_TREND_CONTINUATION_MAX_RANGE_ATR = 1.80;
    private static final double M3_TREND_CONTINUATION_FORCE_ADX = 45.0;

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
        Boolean earlyDirection = earlyCandidateDirection(trigger);
        if (!trigger.hasCandidate() && earlyDirection == null) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE,
                    "MASLOPE_NO_STRONG_STATE state=" + trigger.state());
        }

        MaState prevTrigger = MaSlopeStateClassifier.classifyPreviousPrimary(ctx);
        boolean isLong = trigger.hasCandidate() ? trigger.isLong() : earlyDirection;
        EntryMode triggerMode = entryMode(trigger, prevTrigger, isLong);
        if (triggerMode == null) {
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
        triggerMode = confirmMode(triggerMode, confirm, isLong);
        if (triggerMode == null) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE,
                    "MASLOPE_CONFIRM_NOT_SUPPORT state=" + trigger.state() + " confirm=" + confirm.state());
        }

        EntryQualityDecision entryQuality = entryQuality(ctx, trigger, isLong);
        if (entryQuality.rejectReason() != null) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE, entryQuality.rejectReason());
        }

        String hardReject = hardGate(symbol, ctx, trigger, prevTrigger, confirm,
                interval, isLong, triggerMode, entryQuality.mode());
        if (hardReject != null) {
            return EntryStrategyResult.reject(PATH_MA_SLOPE, hardReject);
        }

        double score = score(ctx, trigger, confirm, isLong, triggerMode, entryQuality.mode());
        double positionScale = positionScale(trigger, confirm, isLong, triggerMode, entryQuality.mode());
        if (entryQuality.mode() == EntryQualityMode.LATE_CONTINUATION) {
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
                reason(trigger, confirm, ctx, isLong, triggerMode, entryQuality.mode(), score),
                entryQuality.mode().name(),
                entryQuality.mode() == EntryQualityMode.LATE_CONTINUATION));
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

    private static String hardGate(String symbol, MarketContext ctx,
                                   MaState trigger, MaState prevTrigger, MaState confirm,
                                   KlineInterval interval, boolean isLong,
                                   EntryMode triggerMode, EntryQualityMode entryMode) {
        if (ctx.qualityFlags.contains("STALE_AGG_TRADE")) {
            return "MASLOPE_STALE_AGG_TRADE";
        }
        double adxThreshold = adxRejectThreshold(interval);
        if (ctx.adx < adxThreshold) {
            return "MASLOPE_ADX_WEAK adx=" + fmt(ctx.adx) + "<" + fmt(adxThreshold);
        }
        if (triggerMode.isEarly() && ctx.adx < adxThreshold + 2.0) {
            return "MASLOPE_EARLY_ADX_NOT_STRONG adx=" + fmt(ctx.adx) + "<" + fmt(adxThreshold + 2.0);
        }
        if (interval == KlineInterval.M3) {
            if (ctx.maAlignment1h == null) {
                return "MASLOPE_3M_1H_MISSING";
            }
            if ("BTCUSDT".equals(symbol)) {
                return "MASLOPE_3M_BTC_NO_EDGE";
            }
            boolean oneHourAligned = EntryStrategySupport.directionAligns(ctx.maAlignment1h, isLong);
            boolean oneHourNeutral = ctx.maAlignment1h == 0;
            if (!oneHourNeutral && !oneHourAligned) {
                return "MASLOPE_3M_1H_AGAINST";
            }
            if (oneHourAligned) {
                if (!"ETHUSDT".equals(symbol)) {
                    return "MASLOPE_3M_1H_ALREADY_TRENDING";
                }
                if (!m3TrendContinuationQuality(symbol, ctx, trigger, confirm, isLong, triggerMode)) {
                    return "MASLOPE_3M_TREND_CONTINUATION_WEAK";
                }
            }
        }
        if (triggerMode == EntryMode.CONFIRMED && isLong && trigger.ma25SlopeAtr() < MA25_SLOPE_MIN_ATR) {
            return "MASLOPE_MA25_FLAT_OR_AGAINST";
        }
        if (triggerMode == EntryMode.CONFIRMED && !isLong && trigger.ma25SlopeAtr() > -MA25_SLOPE_MIN_ATR) {
            return "MASLOPE_MA25_FLAT_OR_AGAINST";
        }
        if (triggerMode.isEarly() && ma25MateriallyAgainst(trigger, isLong)) {
            return "MASLOPE_EARLY_MA25_AGAINST";
        }
        double atrSpikeThreshold = atrSpikeRejectThreshold(interval);
        if (ctx.atrSpikeRatio > atrSpikeThreshold) {
            return "MASLOPE_ATR_SPIKE ratio=" + fmt(ctx.atrSpikeRatio) + ">" + fmt(atrSpikeThreshold);
        }
        if (triggerMode.isEarly() && EntryStrategySupport.directionConflicts(ctx.maAlignment1h, isLong)) {
            return "MASLOPE_EARLY_1H_CONFLICT";
        }
        if (EntryStrategySupport.directionConflicts(ctx.maAlignment1h, isLong) && macdAgainst(ctx, isLong)) {
            return "MASLOPE_MAJOR_CONFLICT";
        }
        if (EntryStrategySupport.microAgainst(ctx, isLong, 0.35)) {
            return "MASLOPE_MICRO_AGAINST";
        }
        if (macdAgainst(ctx, isLong)) {
            return "MASLOPE_MACD_AGAINST";
        }
        if (!klineSupports(ctx, isLong)) {
            return "MASLOPE_KLINE_NOT_SUPPORT";
        }
        if (ctx.bollSqueeze && !compressedBreakoutQuality(ctx, confirm, isLong)) {
            return "MASLOPE_SQUEEZE_BREAKOUT_NOT_CONFIRMED";
        }
        // 3m 均线刚转强但 K 线没持续确认时，很容易变成假启动。
        if (triggerMode.isEarly() && !strongKlineSupports(ctx, isLong)) {
            return "MASLOPE_EARLY_KLINE_NOT_STRONG";
        }
        return null;
    }

    private static EntryQualityDecision entryQuality(MarketContext ctx, MaState trigger, boolean isLong) {
        double distance = trigger.priceDistanceAtr();
        boolean pullbackReclaim = pullbackReclaimSupports(ctx, isLong);

        if (distance <= PRICE_DISTANCE_LAUNCH_ATR) {
            return EntryQualityDecision.accept(EntryQualityMode.LAUNCH);
        }
        if (distance <= PRICE_DISTANCE_PULLBACK_ATR && pullbackReclaim) {
            return EntryQualityDecision.accept(EntryQualityMode.PULLBACK_RECLAIM);
        }
        // LATE_CONTINUATION 在 60d ETH 回测里 6 笔 0 胜（净亏 278U），超 PULLBACK 距离一律拒绝。
        return EntryQualityDecision.reject(
                "MASLOPE_LATE_CHASE_REJECT distanceAtr=" + fmt(distance)
                        + " pullback=" + pullbackReclaim);
    }

    private static boolean klineSupports(MarketContext ctx, boolean isLong) {
        return closeTrendSupportsEither(ctx, isLong)
                && EntryStrategySupport.priceAboveEmaSupports(ctx, isLong)
                && diSupports(ctx, isLong);
    }

    private static boolean strongKlineSupports(MarketContext ctx, boolean isLong) {
        return EntryStrategySupport.closeTrendSupports(ctx.closeTrendClosed3, isLong)
                && EntryStrategySupport.priceAboveEmaSupports(ctx, isLong)
                && diSupports(ctx, isLong)
                && EntryStrategySupport.macdSupports(ctx, isLong);
    }

    private static boolean compressedBreakoutQuality(MarketContext ctx, MaState confirm, boolean isLong) {
        return ctx.bollExpanding5
                && confirm.sameBroadDirection(isLong)
                && bollPositionSupports(ctx, isLong)
                && avg(ctx.volumeRatioClosedSeries) >= 0.90
                && strongKlineSupports(ctx, isLong);
    }

    private static boolean m3TrendContinuationQuality(String symbol, MarketContext ctx, MaState trigger,
                                                      MaState confirm, boolean isLong, EntryMode mode) {
        if (mode == EntryMode.EARLY_MA25_LAG || !trigger.isAccelerating()
                || !confirm.sameStrongDirection(isLong)) {
            return false;
        }
        if (!strongKlineSupports(ctx, isLong)) {
            return false;
        }
        double minVolume = "BTCUSDT".equals(symbol)
                ? BTC_M3_TREND_CONTINUATION_MIN_VOLUME : M3_TREND_CONTINUATION_MIN_VOLUME;
        double maxDistance = "BTCUSDT".equals(symbol)
                ? BTC_M3_TREND_CONTINUATION_MAX_DISTANCE_ATR : M3_TREND_CONTINUATION_MAX_DISTANCE_ATR;
        double maxRange = "BTCUSDT".equals(symbol)
                ? BTC_M3_TREND_CONTINUATION_MAX_RANGE_ATR : M3_TREND_CONTINUATION_MAX_RANGE_ATR;
        if (avg(ctx.volumeRatioClosedSeries) < minVolume) {
            return false;
        }
        if (trigger.priceDistanceAtr() > maxDistance) {
            return false;
        }
        if (!closedCandlePositionSupports(ctx, isLong)) {
            return false;
        }
        if (!rangeWithin(ctx, maxRange)) {
            return false;
        }
        if ("BTCUSDT".equals(symbol) && !structureBreakoutSupports(ctx, isLong)
                && !pullbackReclaimSupports(ctx, isLong)) {
            return false;
        }
        return (ctx.bollExpanding5 || spreadExpansionSupports(trigger, isLong))
                && (structureBreakoutSupports(ctx, isLong)
                || pullbackReclaimSupports(ctx, isLong)
                || highForceTrendContinuation(ctx));
    }

    private static boolean spreadExpansionSupports(MaState trigger, boolean isLong) {
        return isLong
                ? trigger.spreadDeltaAtr() >= SPREAD_DELTA_MIN_ATR * 2.0
                : trigger.spreadDeltaAtr() <= -SPREAD_DELTA_MIN_ATR * 2.0;
    }

    private static boolean closedCandlePositionSupports(MarketContext ctx, boolean isLong) {
        if (ctx.closePositionClosed == null) {
            return false;
        }
        return isLong ? ctx.closePositionClosed >= 0.45 : ctx.closePositionClosed <= 0.45;
    }

    private static boolean structureBreakoutSupports(MarketContext ctx, boolean isLong) {
        return isLong ? ctx.closeBreakoutHigh10Closed : ctx.closeBreakdownLow10Closed;
    }

    public static boolean pullbackReclaimSupports(MarketContext ctx, boolean isLong) {
        if (!isPositive(ctx.atrClosed) || ctx.closeSeriesClosed.size() < 4
                || ctx.ma7SeriesClosed.size() < 4 || !closedCandlePositionSupports(ctx, isLong)) {
            return false;
        }
        double atr = ctx.atrClosed.doubleValue();
        int closeSize = ctx.closeSeriesClosed.size();
        int ma7Size = ctx.ma7SeriesClosed.size();
        double lastClose = ctx.closeSeriesClosed.get(closeSize - 1).doubleValue();
        double lastMa7 = ctx.ma7SeriesClosed.get(ma7Size - 1).doubleValue();
        boolean reclaimed = isLong
                ? lastClose >= lastMa7 - 0.05 * atr
                : lastClose <= lastMa7 + 0.05 * atr;
        if (!reclaimed) {
            return false;
        }
        for (int i = 2; i <= 4; i++) {
            double close = ctx.closeSeriesClosed.get(closeSize - i).doubleValue();
            double ma7 = ctx.ma7SeriesClosed.get(ma7Size - i).doubleValue();
            boolean touched = isLong
                    ? close <= ma7 + 0.20 * atr
                    : close >= ma7 - 0.20 * atr;
            if (touched) {
                return EntryStrategySupport.closeTrendSupports(ctx.closeTrendClosed3, isLong);
            }
        }
        return false;
    }

    private static boolean highForceTrendContinuation(MarketContext ctx) {
        return ctx.adx != null && ctx.adx >= M3_TREND_CONTINUATION_FORCE_ADX
                && avg(ctx.volumeRatioClosedSeries) >= 1.20;
    }

    private static boolean rangeWithin(MarketContext ctx, double maxRangeAtr) {
        return ctx.rangeAtrClosed == null || ctx.rangeAtrClosed <= maxRangeAtr;
    }

    private static boolean bollPositionSupports(MarketContext ctx, boolean isLong) {
        if (ctx.bollPb == null) {
            return false;
        }
        return isLong ? ctx.bollPb >= 58.0 : ctx.bollPb <= 42.0;
    }

    private static boolean closeTrendSupportsEither(MarketContext ctx, boolean isLong) {
        return EntryStrategySupport.closeTrendSupports(ctx.closeTrendClosed3, isLong)
                || EntryStrategySupport.closeTrendSupports(ctx.closeTrend, isLong);
    }

    private static boolean diSupports(MarketContext ctx, boolean isLong) {
        if (ctx.plusDi == null || ctx.minusDi == null) {
            return false;
        }
        return isLong ? ctx.plusDi > ctx.minusDi : ctx.minusDi > ctx.plusDi;
    }

    private static double score(MarketContext ctx, MaState trigger, MaState confirm,
                                boolean isLong, EntryMode triggerMode, EntryQualityMode entryMode) {
        double score = trigger.baseConfidence();
        if (confirm.sameStrongDirection(isLong)) score *= 1.12;
        if (EntryStrategySupport.directionAligns(ctx.maAlignment1h, isLong)) score *= 1.08;
        if (ctx.decisionInterval != KlineInterval.M15
                && EntryStrategySupport.directionAligns(ctx.maAlignment15m, isLong)) score *= 1.06;
        if (EntryStrategySupport.macdSupports(ctx, isLong)) score *= 1.08;
        if (EntryStrategySupport.closeTrendSupports(ctx.closeTrendClosed3, isLong)) score *= 1.06;
        else if (EntryStrategySupport.closeTrendSupports(ctx.closeTrend, isLong)) score *= 1.04;
        if (diSupports(ctx, isLong)) score *= 1.04;
        if (EntryStrategySupport.priceAboveEmaSupports(ctx, isLong)) score *= 1.03;
        if (macdWaterlineSupports(ctx, isLong) && macdFreshReclaim(ctx, isLong)) score *= 1.03;

        double avgClosedVol = avg(ctx.volumeRatioClosedSeries);
        if (avgClosedVol >= 1.50) {
            score *= 1.08;
        } else if (avgClosedVol >= 1.20) {
            score *= 1.04;
        }

        if (ctx.bollExpanding5) score *= 1.05;
        if (EntryStrategySupport.microSupports(ctx, isLong)) score *= 1.03;
        if (entryMode == EntryQualityMode.LATE_CONTINUATION) score *= 0.85;
        if (isFundingCrowded(ctx.fundingRateExtreme, isLong)) score *= 0.92;
        if (triggerMode.isEarly()) score *= 0.90;
        return Math.clamp(score, SCORE_MIN, SCORE_MAX);
    }

    private static double positionScale(MaState trigger, MaState confirm, boolean isLong,
                                        EntryMode triggerMode, EntryQualityMode entryMode) {
        if (triggerMode == EntryMode.EARLY_MA25_LAG) {
            return 0.50;
        }
        if (entryMode == EntryQualityMode.LATE_CONTINUATION) {
            return 0.60;
        }
        return trigger.isAccelerating() && confirm.sameStrongDirection(isLong) ? 0.75 : 0.65;
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

    private static boolean macdWaterlineSupports(MarketContext ctx, boolean isLong) {
        if (ctx.macdDif == null || ctx.macdDea == null) {
            return false;
        }
        int cmp = ctx.macdDif.compareTo(ctx.macdDea);
        if (isLong) {
            return cmp > 0 && ctx.macdDif.signum() > 0 && ctx.macdDea.signum() > 0;
        }
        return cmp < 0 && ctx.macdDif.signum() < 0 && ctx.macdDea.signum() < 0;
    }

    private static boolean macdFreshReclaim(MarketContext ctx, boolean isLong) {
        if (isLong && "golden".equals(ctx.macdCross)) {
            return true;
        }
        if (!isLong && "death".equals(ctx.macdCross)) {
            return true;
        }
        return isLong
                ? EntryStrategySupport.isMacdHistBullish(ctx.macdHistTrend)
                : EntryStrategySupport.isMacdHistBearish(ctx.macdHistTrend);
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
            case M3 -> 25.0;
            case M15 -> 16.0;
            default -> 18.0;
        };
    }

    private static double atrSpikeRejectThreshold(KlineInterval interval) {
        return switch (interval) {
            case M3 -> 2.0;
            case M15 -> 1.5;
            default -> 1.8;
        };
    }

    private static Boolean earlyCandidateDirection(MaState state) {
        if (state.spreadAtr() >= SPREAD_MIN_ATR
                && state.ma7SlopeAtr() >= MA7_SLOPE_STRONG_ATR
                && state.spreadDeltaAtr() >= SPREAD_DELTA_MIN_ATR
                && state.ma25SlopeAtr() >= -MA25_EARLY_LAG_TOLERANCE_ATR) {
            return true;
        }
        if (state.spreadAtr() <= -SPREAD_MIN_ATR
                && state.ma7SlopeAtr() <= -MA7_SLOPE_STRONG_ATR
                && state.spreadDeltaAtr() <= -SPREAD_DELTA_MIN_ATR
                && state.ma25SlopeAtr() <= MA25_EARLY_LAG_TOLERANCE_ATR) {
            return false;
        }
        return null;
    }

    private static EntryMode entryMode(MaState trigger, MaState prevTrigger, boolean isLong) {
        if (trigger.hasCandidate() && prevTrigger.sameStrongDirection(isLong)) {
            return EntryMode.CONFIRMED;
        }
        if (trigger.hasCandidate() && trigger.isAccelerating() && prevTrigger.sameBroadDirection(isLong)) {
            return EntryMode.PRIMARY_KLINE;
        }
        Boolean prevEarlyDirection = earlyCandidateDirection(prevTrigger);
        if (earlyCandidateDirection(trigger) != null
                && ((prevEarlyDirection != null && prevEarlyDirection == isLong)
                || prevTrigger.sameBroadDirection(isLong))) {
            return EntryMode.EARLY_MA25_LAG;
        }
        return null;
    }

    private static EntryMode confirmMode(EntryMode mode, MaState confirm, boolean isLong) {
        if (confirm.sameStrongDirection(isLong)) {
            return mode;
        }
        return null;
    }

    private static boolean ma25MateriallyAgainst(MaState trigger, boolean isLong) {
        return isLong
                ? trigger.ma25SlopeAtr() < -MA25_EARLY_LAG_TOLERANCE_ATR
                : trigger.ma25SlopeAtr() > MA25_EARLY_LAG_TOLERANCE_ATR;
    }

    private static String reason(MaState trigger, MaState confirm, MarketContext ctx,
                                 boolean isLong, EntryMode triggerMode,
                                 EntryQualityMode entryMode, double score) {
        return String.format(Locale.US,
                "均线斜率趋势[%s] entryMode=%s mode=%s state=%s confirm=%s ma7SlopeAtr=%.2f ma7PrevSlopeAtr=%.2f "
                        + "ma25SlopeAtr=%.2f spreadAtr=%.2f spreadDeltaAtr=%.2f adx=%.2f "
                        + "priceDistanceAtr=%.2f atrSpikeRatio=%.2f score=%.2f",
                isLong ? "LONG" : "SHORT",
                entryMode, triggerMode, trigger.state(), confirm.state(),
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

    private enum EntryMode {
        CONFIRMED,
        PRIMARY_KLINE,
        EARLY_MA25_LAG;

        boolean isEarly() {
            return this != CONFIRMED;
        }
    }

    private enum EntryQualityMode {
        LAUNCH,
        PULLBACK_RECLAIM,
        LATE_CONTINUATION
    }

    private record EntryQualityDecision(EntryQualityMode mode, String rejectReason) {
        static EntryQualityDecision accept(EntryQualityMode mode) {
            return new EntryQualityDecision(mode, null);
        }

        static EntryQualityDecision reject(String reason) {
            return new EntryQualityDecision(null, reason);
        }
    }
}
