package com.mawai.wiibservice.agent.research.forecast;

import java.util.ArrayList;
import java.util.List;

/**
 * 训练窗内学习 EWMA/raw vol 与常数 climatology 的方差收缩权重。
 * 当样本外发现 constant 长期更强时，模型能自动把 raw 方差往训练窗均值收缩，而不是硬调 lambda。
 */
public final class VarianceShrinkageVolForecaster implements VolForecaster {

    private static final double VARIANCE_FLOOR = 1e-12;
    private static final double[] DEFAULT_WEIGHTS = {0.0, 0.25, 0.50, 0.75, 1.0};
    private static final int DEFAULT_VALIDATION_OBSERVATIONS = 576;
    private static final double MIN_VALIDATION_QLIKE_IMPROVEMENT = 0.10;

    private final VolForecaster base;
    private final int minObservations;
    private final int validationObservations;
    private final double learnedWeight;
    private final double climatologyVariance;
    private final boolean fitted;

    public VarianceShrinkageVolForecaster(VolForecaster base, int minObservations) {
        this(base, minObservations, DEFAULT_VALIDATION_OBSERVATIONS);
    }

    public VarianceShrinkageVolForecaster(VolForecaster base, int minObservations, int validationObservations) {
        this(base, minObservations, validationObservations, 1.0, 0.0, false);
    }

    private VarianceShrinkageVolForecaster(VolForecaster base, int minObservations,
                                           int validationObservations,
                                           double learnedWeight, double climatologyVariance, boolean fitted) {
        if (base == null) {
            throw new IllegalArgumentException("base vol forecaster 不能为空");
        }
        if (minObservations <= 0) {
            throw new IllegalArgumentException("minObservations 必须为正数");
        }
        if (validationObservations <= 0) {
            throw new IllegalArgumentException("validationObservations 必须为正数");
        }
        this.base = base;
        this.minObservations = minObservations;
        this.validationObservations = validationObservations;
        this.learnedWeight = learnedWeight;
        this.climatologyVariance = climatologyVariance;
        this.fitted = fitted;
    }

    @Override
    public VolForecaster fit(List<TrainingSample> trainSamples) {
        List<TrainingSample> samples = trainSamples == null ? List.of() : trainSamples;
        VolForecaster fittedBase = base.fit(samples);
        List<Point> points = points(fittedBase, samples);
        if (points.size() < minObservations) {
            return new VarianceShrinkageVolForecaster(fittedBase, minObservations, validationObservations,
                    1.0, 0.0, false);
        }

        double climatology = meanRealizedVariance(points);
        List<Point> validation = tailValidation(points, minObservations, validationObservations);
        double rawLoss = qlike(validation, climatology, 1.0);
        double bestWeight = 1.0;
        double bestLoss = rawLoss;
        for (double weight : DEFAULT_WEIGHTS) {
            double loss = qlike(validation, climatology, weight);
            if (loss < bestLoss) {
                bestLoss = loss;
                bestWeight = weight;
            }
        }
        // 收缩只在近期验证窗有明确收益时启用；否则保留 raw EWMA，避免短周期被常数方差压平。
        if (rawLoss - bestLoss < MIN_VALIDATION_QLIKE_IMPROVEMENT) {
            bestWeight = 1.0;
        }
        return new VarianceShrinkageVolForecaster(fittedBase, minObservations, validationObservations,
                bestWeight, climatology, true);
    }

    @Override
    public double forecastSigma(ResearchFeatures features) {
        double rawSigma = base.forecastSigma(features);
        if (!fitted || !Double.isFinite(rawSigma) || rawSigma < 0.0) {
            return rawSigma;
        }
        double rawVariance = Math.max(rawSigma * rawSigma, VARIANCE_FLOOR);
        double variance = learnedWeight * rawVariance + (1.0 - learnedWeight) * climatologyVariance;
        return Math.sqrt(Math.max(variance, VARIANCE_FLOOR));
    }

    @Override
    public String name() {
        return "variance_shrinkage(" + base.name() + ",min=" + minObservations + ")";
    }

    double learnedWeight() {
        return learnedWeight;
    }

    private static List<Point> points(VolForecaster base, List<TrainingSample> samples) {
        List<Point> out = new ArrayList<>(samples.size());
        for (TrainingSample sample : samples) {
            if (sample == null || sample.features() == null || !Double.isFinite(sample.realizedForwardReturn())) {
                continue;
            }
            double sigma = base.forecastSigma(sample.features());
            if (!Double.isFinite(sigma) || sigma < 0.0) {
                continue;
            }
            double rawVariance = Math.max(sigma * sigma, VARIANCE_FLOOR);
            double realizedVariance = Math.max(sample.realizedForwardReturn() * sample.realizedForwardReturn(),
                    VARIANCE_FLOOR);
            out.add(new Point(rawVariance, realizedVariance));
        }
        return out;
    }

    private static double meanRealizedVariance(List<Point> points) {
        double sum = 0.0;
        for (Point p : points) {
            sum += p.realizedVariance();
        }
        return Math.max(sum / points.size(), VARIANCE_FLOOR);
    }

    private static List<Point> tailValidation(List<Point> points, int minObservations, int validationObservations) {
        int validationSize = Math.min(points.size(), Math.max(minObservations, validationObservations));
        return points.subList(points.size() - validationSize, points.size());
    }

    private static double qlike(List<Point> points, double climatologyVariance, double weight) {
        double sum = 0.0;
        for (Point p : points) {
            double h = Math.max(weight * p.rawVariance() + (1.0 - weight) * climatologyVariance, VARIANCE_FLOOR);
            double x = Math.max(p.realizedVariance(), VARIANCE_FLOOR);
            sum += x / h - Math.log(x / h) - 1.0;
        }
        return sum / points.size();
    }

    private record Point(double rawVariance, double realizedVariance) {}
}
