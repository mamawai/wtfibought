package com.mawai.wiibquant.agent.research.metrics;

/**
 * 两条预测 loss 序列的样本外比较：lossDiff = baselineLoss - modelLoss。
 * meanLossDiff > 0 表示 model 平均损失更低；pValue 是单侧 Newey-West 正态近似。
 */
public record ForecastAccuracyComparison(
        double meanLossDiff,
        double dmStatistic,
        double pValue,
        int n,
        int maxLag
) {

    public boolean modelBetterAt(double alpha) {
        return meanLossDiff > 0 && pValue <= alpha;
    }

    public static ForecastAccuracyComparison compareLosses(double[] modelLosses, double[] baselineLosses, int maxLag) {
        if (modelLosses == null || baselineLosses == null) {
            throw new IllegalArgumentException("loss 序列不能为空");
        }
        if (modelLosses.length != baselineLosses.length) {
            throw new IllegalArgumentException("loss 序列长度不一致: "
                    + modelLosses.length + " vs " + baselineLosses.length);
        }
        int n = modelLosses.length;
        if (n == 0) return new ForecastAccuracyComparison(0.0, 0.0, 1.0, 0, 0);

        double[] diff = new double[n];
        double mean = 0.0;
        for (int i = 0; i < n; i++) {
            diff[i] = baselineLosses[i] - modelLosses[i];
            mean += diff[i];
        }
        mean /= n;

        int lag = Math.max(0, Math.min(maxLag, n - 1));
        double lrv = longRunVariance(diff, mean, lag);
        if (lrv <= 0.0) {
            if (mean > 0.0) return new ForecastAccuracyComparison(mean, Double.POSITIVE_INFINITY, 0.0, n, lag);
            if (mean < 0.0) return new ForecastAccuracyComparison(mean, Double.NEGATIVE_INFINITY, 1.0, n, lag);
            return new ForecastAccuracyComparison(0.0, 0.0, 1.0, n, lag);
        }

        double statistic = mean / Math.sqrt(lrv / n);
        double pValue = 1.0 - NormalDistribution.cdf(statistic); // 单侧：model 是否更好
        return new ForecastAccuracyComparison(mean, statistic, pValue, n, lag);
    }

    private static double longRunVariance(double[] diff, double mean, int maxLag) {
        int n = diff.length;
        double out = autocovariance(diff, mean, 0);
        for (int lag = 1; lag <= maxLag; lag++) {
            double weight = 1.0 - (double) lag / (maxLag + 1);
            out += 2.0 * weight * autocovariance(diff, mean, lag);
        }
        return Math.max(0.0, out);
    }

    private static double autocovariance(double[] diff, double mean, int lag) {
        double sum = 0.0;
        for (int i = lag; i < diff.length; i++) {
            sum += (diff[i] - mean) * (diff[i - lag] - mean);
        }
        return sum / diff.length;
    }
}
