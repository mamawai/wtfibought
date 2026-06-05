package com.mawai.wiibservice.agent.research.forecast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 训练窗内的分桶 vol 校准器：按 raw sigma 等频分桶，学习 sqrt(mean(r²)/mean(rawSigma²))。
 * 只用 {@link TrainingSample#realizedForwardReturn()}，样本不足的窗口退回 raw sigma，避免把校准做成贴 test。
 */
public final class BucketCalibratedVolForecaster implements VolForecaster {

    private static final double VARIANCE_FLOOR = 1e-12;

    private final VolForecaster base;
    private final int bucketCount;
    private final int minBucketObservations;
    private final List<Bucket> buckets;

    public BucketCalibratedVolForecaster(VolForecaster base, int bucketCount, int minBucketObservations) {
        this(base, bucketCount, minBucketObservations, List.of());
    }

    private BucketCalibratedVolForecaster(VolForecaster base, int bucketCount, int minBucketObservations,
                                          List<Bucket> buckets) {
        if (base == null) {
            throw new IllegalArgumentException("base vol forecaster 不能为空");
        }
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount 必须为正数");
        }
        if (minBucketObservations <= 0) {
            throw new IllegalArgumentException("minBucketObservations 必须为正数");
        }
        this.base = base;
        this.bucketCount = bucketCount;
        this.minBucketObservations = minBucketObservations;
        this.buckets = List.copyOf(buckets);
    }

    @Override
    public VolForecaster fit(List<TrainingSample> trainSamples) {
        List<TrainingSample> samples = trainSamples == null ? List.of() : trainSamples;
        VolForecaster fittedBase = base.fit(samples);
        List<Point> points = calibrationPoints(fittedBase, samples);
        int learnedBucketCount = Math.min(bucketCount, points.size() / minBucketObservations);
        if (learnedBucketCount <= 0) {
            return new BucketCalibratedVolForecaster(fittedBase, bucketCount, minBucketObservations, List.of());
        }

        points.sort(Comparator.comparingDouble(Point::sigma));
        List<Bucket> learned = new ArrayList<>(learnedBucketCount);
        for (int b = 0; b < learnedBucketCount; b++) {
            int start = b * points.size() / learnedBucketCount;
            int end = (b + 1) * points.size() / learnedBucketCount;
            learned.add(bucket(points.subList(start, end)));
        }
        return new BucketCalibratedVolForecaster(fittedBase, bucketCount, minBucketObservations, learned);
    }

    @Override
    public double forecastSigma(ResearchFeatures features) {
        double rawSigma = base.forecastSigma(features);
        if (buckets.isEmpty() || !Double.isFinite(rawSigma) || rawSigma <= 0.0) {
            return rawSigma;
        }
        return rawSigma * scaleFor(rawSigma);
    }

    @Override
    public String name() {
        return "bucket_calibrated(" + base.name() + ",b=" + bucketCount + ",min=" + minBucketObservations + ")";
    }

    private static List<Point> calibrationPoints(VolForecaster base, List<TrainingSample> samples) {
        List<Point> points = new ArrayList<>(samples.size());
        for (TrainingSample sample : samples) {
            if (sample == null || sample.features() == null || !Double.isFinite(sample.realizedForwardReturn())) {
                continue;
            }
            double sigma = base.forecastSigma(sample.features());
            if (!Double.isFinite(sigma) || sigma < 0.0) {
                continue;
            }
            double predictedVariance = Math.max(sigma * sigma, VARIANCE_FLOOR);
            double realizedVariance = Math.max(sample.realizedForwardReturn() * sample.realizedForwardReturn(), VARIANCE_FLOOR);
            points.add(new Point(sigma, predictedVariance, realizedVariance));
        }
        return points;
    }

    private static Bucket bucket(List<Point> points) {
        double predictedSum = 0.0;
        double realizedSum = 0.0;
        for (Point p : points) {
            predictedSum += p.predictedVariance();
            realizedSum += p.realizedVariance();
        }
        double scale = Math.sqrt(realizedSum / predictedSum);
        if (!Double.isFinite(scale) || scale <= 0.0) {
            scale = 1.0;
        }
        return new Bucket(points.get(points.size() - 1).sigma(), scale);
    }

    private double scaleFor(double sigma) {
        for (Bucket bucket : buckets) {
            if (sigma <= bucket.upperSigma()) {
                return bucket.scale();
            }
        }
        return buckets.get(buckets.size() - 1).scale();
    }

    private record Point(double sigma, double predictedVariance, double realizedVariance) {}

    private record Bucket(double upperSigma, double scale) {}
}
