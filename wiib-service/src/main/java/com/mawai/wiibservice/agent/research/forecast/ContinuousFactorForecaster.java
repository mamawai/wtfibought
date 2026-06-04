package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.factor.ContinuousFactorVector;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 训练窗内学习连续因子方向的轻量预测器。
 * 只用 walk-forward train samples 估每个因子与事后收益的相关方向；不网格调参，不跨窗累积状态。
 */
public final class ContinuousFactorForecaster implements Forecaster {

    private static final int FACTOR_COUNT = 6;
    private static final int MIN_TRAIN_SAMPLES = 5;
    public static final double DEFAULT_EPSILON = 0.05;

    private final String name;
    private final double epsilon;
    private final double[] means;
    private final double[] scales;
    private final double[] weights;
    private final boolean trained;

    public ContinuousFactorForecaster() {
        this("continuous_factor_ic", DEFAULT_EPSILON,
                new double[FACTOR_COUNT], new double[FACTOR_COUNT], new double[FACTOR_COUNT], false);
    }

    private ContinuousFactorForecaster(String name, double epsilon,
                                       double[] means, double[] scales, double[] weights, boolean trained) {
        this.name = name;
        this.epsilon = epsilon;
        this.means = Arrays.copyOf(means, FACTOR_COUNT);
        this.scales = Arrays.copyOf(scales, FACTOR_COUNT);
        this.weights = Arrays.copyOf(weights, FACTOR_COUNT);
        this.trained = trained;
    }

    public static ContinuousFactorForecaster defaults() {
        return new ContinuousFactorForecaster();
    }

    @Override
    public Forecaster fit(List<TrainingSample> trainSamples) {
        if (trainSamples == null || trainSamples.size() < MIN_TRAIN_SAMPLES) {
            return new ContinuousFactorForecaster(name, epsilon,
                    new double[FACTOR_COUNT], new double[FACTOR_COUNT], new double[FACTOR_COUNT], false);
        }

        double[][] xs = new double[trainSamples.size()][FACTOR_COUNT];
        double[] ys = new double[trainSamples.size()];
        double[] meanX = new double[FACTOR_COUNT];
        double meanY = 0.0;
        int n = 0;

        for (TrainingSample sample : trainSamples) {
            if (sample == null || sample.features() == null) continue;
            double y = realizedReturn(sample.realizedLongReturn());
            if (!Double.isFinite(y)) continue;
            ContinuousFactorVector v = sample.features().continuousFactors();
            for (int j = 0; j < FACTOR_COUNT; j++) {
                double x = factor(v, j);
                xs[n][j] = Double.isFinite(x) ? x : 0.0;
                meanX[j] += xs[n][j];
            }
            ys[n] = y;
            meanY += y;
            n++;
        }

        if (n < MIN_TRAIN_SAMPLES) {
            return new ContinuousFactorForecaster();
        }

        for (int j = 0; j < FACTOR_COUNT; j++) meanX[j] /= n;
        meanY /= n;

        double[] ssX = new double[FACTOR_COUNT];
        double[] covXY = new double[FACTOR_COUNT];
        double ssY = 0.0;
        for (int i = 0; i < n; i++) {
            double dy = ys[i] - meanY;
            ssY += dy * dy;
            for (int j = 0; j < FACTOR_COUNT; j++) {
                double dx = xs[i][j] - meanX[j];
                ssX[j] += dx * dx;
                covXY[j] += dx * dy;
            }
        }

        double[] scale = new double[FACTOR_COUNT];
        double[] weight = new double[FACTOR_COUNT];
        for (int j = 0; j < FACTOR_COUNT; j++) {
            scale[j] = n < 2 ? 0.0 : Math.sqrt(ssX[j] / (n - 1));
            weight[j] = correlation(covXY[j], ssX[j], ssY);
        }
        return new ContinuousFactorForecaster(name, epsilon, meanX, scale, weight, true);
    }

    @Override
    public Forecast forecast(ResearchFeatures features) {
        if (!trained || features == null) return Forecast.flat();
        ContinuousFactorVector v = features.continuousFactors();
        double score = 0.0;
        double weightSum = 0.0;
        for (int j = 0; j < FACTOR_COUNT; j++) {
            double w = weights[j];
            double scale = scales[j];
            if (w == 0.0 || scale == 0.0) continue;
            double z = (factor(v, j) - means[j]) / scale;
            score += w * Math.tanh(z);
            weightSum += Math.abs(w);
        }
        if (weightSum == 0.0) return Forecast.flat();
        double s = score / weightSum;
        int dir = s > epsilon ? 1 : (s < -epsilon ? -1 : 0);
        return new Forecast(dir, Math.min(1.0, Math.abs(s)));
    }

    @Override
    public String name() {
        return name;
    }

    private static double realizedReturn(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private static double correlation(double cov, double ssX, double ssY) {
        if (ssX == 0.0 || ssY == 0.0) return 0.0;
        double corr = cov / Math.sqrt(ssX * ssY);
        if (!Double.isFinite(corr)) return 0.0;
        return Math.max(-1.0, Math.min(1.0, corr));
    }

    private static double factor(ContinuousFactorVector v, int index) {
        ContinuousFactorVector safe = v == null ? ContinuousFactorVector.neutral() : v;
        return switch (index) {
            case 0 -> safe.riskAdjustedMomentum();
            case 1 -> safe.shortReversal();
            case 2 -> safe.fundingCarry();
            case 3 -> safe.volumeZScore();
            case 4 -> safe.amihudIlliquidity();
            case 5 -> safe.residualMomentum();
            default -> 0.0;
        };
    }
}
