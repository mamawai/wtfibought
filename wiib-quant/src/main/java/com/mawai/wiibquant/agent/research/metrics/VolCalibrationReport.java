package com.mawai.wiibquant.agent.research.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 波动率校准诊断：按预测 sigma 从低到高等频分桶，对比预测方差 h 与实现方差 r²。
 * ratio = mean(r²) / mean(h)；>1 表示低估波动，<1 表示高估波动。
 */
public record VolCalibrationReport(
        int n,
        int bucketCount,
        double meanPredictedVariance,
        double meanRealizedVariance,
        double realizedToPredictedRatio,
        List<Bucket> buckets
) {

    private static final double VARIANCE_FLOOR = 1e-12;

    public record Bucket(
            int bucketIndex,
            int n,
            double minPredictedSigma,
            double maxPredictedSigma,
            double meanPredictedVariance,
            double meanRealizedVariance,
            double realizedToPredictedRatio
    ) {}

    public static VolCalibrationReport evaluate(double[] predictedSigma, double[] realizedReturn, int requestedBuckets) {
        if (predictedSigma == null || realizedReturn == null) {
            throw new IllegalArgumentException("预测/实现序列不能为空");
        }
        if (predictedSigma.length != realizedReturn.length) {
            throw new IllegalArgumentException("预测与实现长度不一致: "
                    + predictedSigma.length + " vs " + realizedReturn.length);
        }
        int n = predictedSigma.length;
        if (n == 0 || requestedBuckets <= 0) {
            return new VolCalibrationReport(n, 0, 0.0, 0.0, 0.0, List.of());
        }

        List<Point> points = new ArrayList<>(n);
        double predSum = 0.0;
        double realizedSum = 0.0;
        for (int i = 0; i < n; i++) {
            double sigma = Math.max(predictedSigma[i], 0.0);
            double predVar = Math.max(sigma * sigma, VARIANCE_FLOOR);
            double realizedVar = Math.max(realizedReturn[i] * realizedReturn[i], VARIANCE_FLOOR);
            points.add(new Point(sigma, predVar, realizedVar));
            predSum += predVar;
            realizedSum += realizedVar;
        }
        points.sort(Comparator.comparingDouble(Point::sigma));

        int bucketCount = Math.min(requestedBuckets, n);
        List<Bucket> buckets = new ArrayList<>(bucketCount);
        for (int b = 0; b < bucketCount; b++) {
            int start = b * n / bucketCount;
            int end = (b + 1) * n / bucketCount;
            buckets.add(bucket(b, points.subList(start, end)));
        }

        double meanPred = predSum / n;
        double meanRealized = realizedSum / n;
        return new VolCalibrationReport(n, bucketCount, meanPred, meanRealized,
                ratio(meanRealized, meanPred), List.copyOf(buckets));
    }

    private static Bucket bucket(int index, List<Point> points) {
        int n = points.size();
        double predSum = 0.0;
        double realizedSum = 0.0;
        for (Point p : points) {
            predSum += p.predictedVariance();
            realizedSum += p.realizedVariance();
        }
        double meanPred = predSum / n;
        double meanRealized = realizedSum / n;
        return new Bucket(index, n, points.get(0).sigma(), points.get(n - 1).sigma(),
                meanPred, meanRealized, ratio(meanRealized, meanPred));
    }

    private static double ratio(double numerator, double denominator) {
        return denominator <= 0.0 ? 0.0 : numerator / denominator;
    }

    private record Point(double sigma, double predictedVariance, double realizedVariance) {}
}
