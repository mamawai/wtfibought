package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.factor.FactorMath;
import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.List;

/**
 * 训练窗 horizon realized return 的常数 RMS 波动率腿。
 * 用途很朴素：当时变 EWMA 信号长期输给 constant baseline 时，把这个可交易时点可知的常数口径纳入模型候选。
 */
public final class ClimatologyVolForecaster implements VolForecaster {

    private static final double VARIANCE_FLOOR = 1e-12;

    private final ForecastHorizon horizon;
    private final VolForecaster fallback;
    private final int minObservations;
    private final boolean fitted;
    private double sumSq;
    private int count;
    private long lastAcceptedStartCloseTime;

    public ClimatologyVolForecaster(ForecastHorizon horizon, VolForecaster fallback, int minObservations) {
        this(horizon, fallback, minObservations, 0.0, 0, Long.MIN_VALUE, false);
    }

    private ClimatologyVolForecaster(ForecastHorizon horizon, VolForecaster fallback, int minObservations,
                                     double sumSq, int count, long lastAcceptedStartCloseTime, boolean fitted) {
        if (horizon == null) {
            throw new IllegalArgumentException("horizon 不能为空");
        }
        if (fallback == null) {
            throw new IllegalArgumentException("fallback vol forecaster 不能为空");
        }
        if (minObservations <= 0) {
            throw new IllegalArgumentException("minObservations 必须为正数");
        }
        this.horizon = horizon;
        this.fallback = fallback;
        this.minObservations = minObservations;
        this.sumSq = sumSq;
        this.count = count;
        this.lastAcceptedStartCloseTime = lastAcceptedStartCloseTime;
        this.fitted = fitted;
    }

    @Override
    public VolForecaster fit(List<TrainingSample> trainSamples) {
        List<TrainingSample> samples = trainSamples == null ? List.of() : trainSamples;
        VolForecaster fittedFallback = fallback.fit(samples);
        double sumSq = 0.0;
        int n = 0;
        long lastStart = Long.MIN_VALUE;
        for (TrainingSample sample : samples) {
            if (sample == null || !Double.isFinite(sample.realizedForwardReturn())) {
                continue;
            }
            double ret = sample.realizedForwardReturn();
            sumSq += ret * ret;
            n++;
            lastStart = Math.max(lastStart, featureEndCloseTime(sample.features()));
        }
        if (n < minObservations) {
            return new ClimatologyVolForecaster(horizon, fittedFallback, minObservations,
                    0.0, 0, Long.MIN_VALUE, false);
        }
        return new ClimatologyVolForecaster(horizon, fittedFallback, minObservations,
                sumSq, n, lastStart, true);
    }

    @Override
    public double forecastSigma(ResearchFeatures features) {
        if (!fitted) {
            return fallback.forecastSigma(features);
        }
        updateKnownHorizonReturns(features);
        return Math.sqrt(Math.max(sumSq / Math.max(1, count), VARIANCE_FLOOR));
    }

    @Override
    public String name() {
        return "climatology_vol(" + fallback.name() + ",min=" + minObservations + ")";
    }

    private void updateKnownHorizonReturns(ResearchFeatures features) {
        List<KlineBar> bars = features == null || features.barsUpToNow() == null
                ? List.of()
                : features.barsUpToNow();
        if (bars.size() < 2) {
            return;
        }
        long barMillis = features.inferredBarMillis();
        if (barMillis <= 0L || horizon.millis() % barMillis != 0L) {
            return;
        }
        int horizonBars = Math.toIntExact(horizon.millis() / barMillis);
        if (horizonBars <= 0 || bars.size() <= horizonBars) {
            return;
        }
        int startEndExclusive = bars.size() - horizonBars;
        // 回看窗很长，二分跳过已经纳入 climatology 的 horizon start。
        for (int start = firstStartAfter(bars, lastAcceptedStartCloseTime, startEndExclusive);
             start < startEndExclusive; start++) {
            KlineBar entry = bars.get(start);
            long startCloseTime = entry.closeTime();
            KlineBar exit = bars.get(start + horizonBars);
            double ret = FactorMath.logReturn(exit.close().doubleValue(), entry.close().doubleValue());
            if (Double.isFinite(ret)) {
                sumSq += ret * ret;
                count++;
                lastAcceptedStartCloseTime = startCloseTime;
            }
        }
    }

    private static int firstStartAfter(List<KlineBar> bars, long lastAcceptedStartCloseTime, int endExclusive) {
        int lo = 0;
        int hi = Math.max(0, Math.min(endExclusive, bars.size()));
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (bars.get(mid).closeTime() <= lastAcceptedStartCloseTime) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static long featureEndCloseTime(ResearchFeatures features) {
        List<KlineBar> bars = features == null || features.barsUpToNow() == null
                ? List.of()
                : features.barsUpToNow();
        return bars.isEmpty() ? Long.MIN_VALUE : bars.get(bars.size() - 1).closeTime();
    }
}
