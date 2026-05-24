package com.mawai.wiibservice.agent.trading.maslope;

import com.mawai.wiibservice.agent.trading.runtime.MarketContext;

import java.math.BigDecimal;
import java.util.List;

/**
 * MaSlope 入场和退出共用的均线斜率状态机。
 * 所有判断只吃闭合 MA/ATR，避免实盘未收盘插针和回测闭合 K 线口径打架。
 */
public final class MaSlopeStateClassifier {

    public static final int MIN_SERIES_SIZE = 8;
    public static final int MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM = 9;
    public static final double MA7_SLOPE_MIN_ATR = 0.03;
    public static final double MA7_SLOPE_STRONG_ATR = 0.06;
    public static final double MA25_SLOPE_MIN_ATR = 0.01;
    public static final double SPREAD_MIN_ATR = 0.20;
    public static final double SPREAD_DELTA_MIN_ATR = 0.01;

    private MaSlopeStateClassifier() {
    }

    public static MaState classifyPrimary(MarketContext ctx) {
        if (ctx == null) {
            return MaState.neutral();
        }
        return classify(ctx.ma7SeriesClosed, ctx.ma25SeriesClosed, ctx.atrClosed, ctx.price);
    }

    public static MaState classifyPreviousPrimary(MarketContext ctx) {
        if (ctx == null || ctx.ma7SeriesClosed.size() < MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM
                || ctx.ma25SeriesClosed.size() < MIN_SERIES_SIZE_WITH_PREVIOUS_CONFIRM) {
            return MaState.neutral();
        }
        return classify(
                ctx.ma7SeriesClosed.subList(0, ctx.ma7SeriesClosed.size() - 1),
                ctx.ma25SeriesClosed.subList(0, ctx.ma25SeriesClosed.size() - 1),
                previousClosedAtr(ctx),
                ctx.price);
    }

    public static MaState classifyConfirm(MarketContext ctx) {
        if (ctx == null) {
            return MaState.neutral();
        }
        return classify(ctx.confirmMa7SeriesClosed, ctx.confirmMa25SeriesClosed, ctx.confirmAtr, ctx.price);
    }

    private static BigDecimal previousClosedAtr(MarketContext ctx) {
        if (ctx.atrSeriesClosed != null && ctx.atrSeriesClosed.size() >= 2) {
            return ctx.atrSeriesClosed.get(ctx.atrSeriesClosed.size() - 2);
        }
        return ctx.atrClosed;
    }

    public static MaState classify(List<BigDecimal> ma7SeriesClosed,
                                   List<BigDecimal> ma25SeriesClosed,
                                   BigDecimal atr,
                                   BigDecimal price) {
        if (ma7SeriesClosed == null || ma25SeriesClosed == null
                || ma7SeriesClosed.size() < MIN_SERIES_SIZE || ma25SeriesClosed.size() < MIN_SERIES_SIZE
                || atr == null || atr.signum() <= 0 || price == null || price.signum() <= 0) {
            return MaState.neutral();
        }

        double atrValue = atr.doubleValue();
        int ma7Size = ma7SeriesClosed.size();
        int ma25Size = ma25SeriesClosed.size();
        double ma7Slope = linearRegressionSlope(ma7SeriesClosed.subList(ma7Size - 5, ma7Size)) / atrValue;
        double ma7PrevSlope = linearRegressionSlope(ma7SeriesClosed.subList(ma7Size - 6, ma7Size - 1)) / atrValue;
        double ma25Slope = linearRegressionSlope(ma25SeriesClosed.subList(ma25Size - 5, ma25Size)) / atrValue;
        double ma7Last = ma7SeriesClosed.get(ma7Size - 1).doubleValue();
        double ma25Last = ma25SeriesClosed.get(ma25Size - 1).doubleValue();
        double ma7Prev = ma7SeriesClosed.get(ma7Size - 5).doubleValue();
        double ma25Prev = ma25SeriesClosed.get(ma25Size - 5).doubleValue();
        double spread = (ma7Last - ma25Last) / atrValue;
        double spreadPrev = (ma7Prev - ma25Prev) / atrValue;
        double spreadDelta = (spread - spreadPrev) / 4.0;
        double priceDistance = Math.abs(price.doubleValue() - ma25Last) / atrValue;

        MaSignalState state = classifyState(spread, spreadDelta, ma7Slope, ma7PrevSlope, ma25Slope);
        return new MaState(state, ma7Slope, ma7PrevSlope, ma25Slope, spread, spreadDelta, priceDistance);
    }

    public static double linearRegressionSlope(List<BigDecimal> values) {
        if (values == null || values.size() < 2) {
            return 0.0;
        }
        int n = values.size();
        double xMean = (n - 1) / 2.0;
        double yMean = values.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
        double numerator = 0.0;
        double denominator = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = i - xMean;
            numerator += dx * (values.get(i).doubleValue() - yMean);
            denominator += dx * dx;
        }
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }

    private static MaSignalState classifyState(double spread, double spreadDelta,
                                               double ma7Slope, double ma7PrevSlope,
                                               double ma25Slope) {
        if (spread >= SPREAD_MIN_ATR
                && ma7Slope >= MA7_SLOPE_MIN_ATR
                && spreadDelta >= SPREAD_DELTA_MIN_ATR
                && ma25Slope >= MA25_SLOPE_MIN_ATR) {
            return ma7Slope >= MA7_SLOPE_STRONG_ATR
                    ? MaSignalState.UP_ACCELERATING : MaSignalState.STRONG_UP;
        }
        if (spread <= -SPREAD_MIN_ATR
                && ma7Slope <= -MA7_SLOPE_MIN_ATR
                && spreadDelta <= -SPREAD_DELTA_MIN_ATR
                && ma25Slope <= -MA25_SLOPE_MIN_ATR) {
            return ma7Slope <= -MA7_SLOPE_STRONG_ATR
                    ? MaSignalState.DOWN_ACCELERATING : MaSignalState.STRONG_DOWN;
        }

        boolean ma7Flat = Math.abs(ma7Slope) < MA7_SLOPE_MIN_ATR;
        boolean posToNeg = ma7PrevSlope >= MA7_SLOPE_MIN_ATR && ma7Slope <= -MA7_SLOPE_MIN_ATR;
        boolean negToPos = ma7PrevSlope <= -MA7_SLOPE_MIN_ATR && ma7Slope >= MA7_SLOPE_MIN_ATR;
        if (spread > 0 && (ma7Flat || posToNeg || ma7Slope < 0
                || spreadDelta <= 0 || ma25Slope < MA25_SLOPE_MIN_ATR)) {
            return MaSignalState.UP_DECELERATING;
        }
        if (spread < 0 && (ma7Flat || negToPos || ma7Slope > 0
                || spreadDelta >= 0 || ma25Slope > -MA25_SLOPE_MIN_ATR)) {
            return MaSignalState.DOWN_DECELERATING;
        }
        return MaSignalState.NEUTRAL_CHOP;
    }

    public enum MaSignalState {
        STRONG_UP,
        UP_ACCELERATING,
        UP_DECELERATING,
        STRONG_DOWN,
        DOWN_ACCELERATING,
        DOWN_DECELERATING,
        NEUTRAL_CHOP
    }

    public record MaState(MaSignalState state,
                          double ma7SlopeAtr,
                          double ma7PrevSlopeAtr,
                          double ma25SlopeAtr,
                          double spreadAtr,
                          double spreadDeltaAtr,
                          double priceDistanceAtr) {

        public static MaState neutral() {
            return new MaState(MaSignalState.NEUTRAL_CHOP, 0, 0, 0, 0, 0, 0);
        }

        public boolean hasCandidate() {
            return state == MaSignalState.STRONG_UP
                    || state == MaSignalState.UP_ACCELERATING
                    || state == MaSignalState.STRONG_DOWN
                    || state == MaSignalState.DOWN_ACCELERATING;
        }

        public boolean isLong() {
            return state == MaSignalState.STRONG_UP || state == MaSignalState.UP_ACCELERATING;
        }

        public boolean isAccelerating() {
            return state == MaSignalState.UP_ACCELERATING || state == MaSignalState.DOWN_ACCELERATING;
        }

        public double baseConfidence() {
            return isAccelerating() ? 0.76 : 0.70;
        }

        public boolean stronglyAgainst(boolean candidateLong) {
            if (candidateLong) {
                return state == MaSignalState.STRONG_DOWN || state == MaSignalState.DOWN_ACCELERATING;
            }
            return state == MaSignalState.STRONG_UP || state == MaSignalState.UP_ACCELERATING;
        }

        public boolean sameStrongDirection(boolean candidateLong) {
            if (candidateLong) {
                return state == MaSignalState.STRONG_UP || state == MaSignalState.UP_ACCELERATING;
            }
            return state == MaSignalState.STRONG_DOWN || state == MaSignalState.DOWN_ACCELERATING;
        }

        public boolean sameBroadDirection(boolean positionLong) {
            if (positionLong) {
                return state == MaSignalState.STRONG_UP
                        || state == MaSignalState.UP_ACCELERATING
                        || state == MaSignalState.UP_DECELERATING;
            }
            return state == MaSignalState.STRONG_DOWN
                    || state == MaSignalState.DOWN_ACCELERATING
                    || state == MaSignalState.DOWN_DECELERATING;
        }

        public boolean extinguishingAgainstPosition(boolean positionLong, double exitSlopeAtr) {
            if (positionLong) {
                return (state == MaSignalState.UP_DECELERATING
                        || state == MaSignalState.DOWN_DECELERATING
                        || state == MaSignalState.NEUTRAL_CHOP)
                        && ma7SlopeAtr <= exitSlopeAtr;
            }
            return (state == MaSignalState.DOWN_DECELERATING
                    || state == MaSignalState.UP_DECELERATING
                    || state == MaSignalState.NEUTRAL_CHOP)
                    && ma7SlopeAtr >= -exitSlopeAtr;
        }
    }
}
