package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.factor.FactorMath;
import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.time.Duration;
import java.util.List;

/**
 * log-HAR-RV 波动率预测腿：用短/中/长三个真实时间窗口的 realized variance 均值回归下一期 log(RV)。
 * 只读 features.barsUpToNow；样本不足或 OLS 病态时退回 fallback，避免为了一个模型牺牲评估稳定性。
 */
public final class HarRvVolForecaster implements VolForecaster {

    private static final double VAR_FLOOR = 1e-12;
    private static final double RIDGE = 1e-8;
    private static final int COEFFICIENTS = 4; // intercept + short/medium/long RV
    private static final long DEFAULT_BAR_MILLIS = Duration.ofMinutes(5).toMillis();

    private final int shortWindow;
    private final int mediumWindow;
    private final int longWindow;
    private final int minObservations;
    private final VolForecaster fallback;

    public HarRvVolForecaster(int shortWindow, int mediumWindow, int longWindow,
                              int minObservations, VolForecaster fallback) {
        if (shortWindow < 1 || mediumWindow < shortWindow || longWindow < mediumWindow) {
            throw new IllegalArgumentException("HAR 窗口必须满足 1 <= short <= medium <= long");
        }
        if (minObservations < COEFFICIENTS) {
            throw new IllegalArgumentException("minObservations 不能小于 " + COEFFICIENTS);
        }
        this.shortWindow = shortWindow;
        this.mediumWindow = mediumWindow;
        this.longWindow = longWindow;
        this.minObservations = minObservations;
        if (fallback == null) {
            throw new IllegalArgumentException("fallback 不能为空");
        }
        this.fallback = fallback;
    }

    /** 默认按 5m 决策 bar：1h / 6h / 24h；fallback 用现有 EWMA 默认口径。 */
    public static HarRvVolForecaster defaults(double fallbackLambda) {
        return defaults(fallbackLambda, DEFAULT_BAR_MILLIS);
    }

    public static HarRvVolForecaster defaults(double fallbackLambda, long barMillis) {
        return new HarRvVolForecaster(
                bars(Duration.ofHours(1), barMillis),
                bars(Duration.ofHours(6), barMillis),
                bars(Duration.ofHours(24), barMillis),
                bars(Duration.ofHours(12), barMillis),
                new EwmaVolForecaster(fallbackLambda));
    }

    @Override
    public double forecastSigma(ResearchFeatures features) {
        List<KlineBar> bars = features == null ? List.of() : features.barsUpToNow();
        double fallbackSigma = fallback.forecastSigma(features);
        double[] rv = realizedVariance(bars);
        if (rv.length < longWindow + minObservations) {
            return fallbackSigma;
        }

        double[][] xtx = new double[COEFFICIENTS][COEFFICIENTS];
        double[] xty = new double[COEFFICIENTS];
        int observations = 0;
        for (int target = longWindow; target < rv.length; target++) {
            double[] x = featuresFor(rv, target);
            double y = Math.log(Math.max(rv[target], VAR_FLOOR));
            accumulate(xtx, xty, x, y);
            observations++;
        }
        if (observations < minObservations) {
            return fallbackSigma;
        }
        for (int j = 1; j < COEFFICIENTS; j++) {
            xtx[j][j] += RIDGE;
        }
        double[] beta = solve(xtx, xty);
        if (beta == null) {
            return fallbackSigma;
        }

        double[] current = featuresFor(rv, rv.length);
        double logVar = dot(beta, current);
        double variance = Math.exp(logVar + 0.5 * residualVariance(rv, beta)); // lognormal 回转修正
        if (!Double.isFinite(variance) || variance <= 0.0) {
            return fallbackSigma;
        }
        return Math.sqrt(Math.max(variance, VAR_FLOOR));
    }

    @Override
    public String name() {
        return "har_rv_log(" + shortWindow + "," + mediumWindow + "," + longWindow + ")";
    }

    private double[] featuresFor(double[] rv, int endExclusive) {
        return new double[]{
                1.0,
                Math.log(Math.max(mean(rv, endExclusive, shortWindow), VAR_FLOOR)),
                Math.log(Math.max(mean(rv, endExclusive, mediumWindow), VAR_FLOOR)),
                Math.log(Math.max(mean(rv, endExclusive, longWindow), VAR_FLOOR))
        };
    }

    private static double[] realizedVariance(List<KlineBar> bars) {
        if (bars == null || bars.size() < 2) return new double[0];
        double[] out = new double[bars.size() - 1];
        double prev = bars.getFirst().close().doubleValue();
        for (int i = 1; i < bars.size(); i++) {
            double close = bars.get(i).close().doubleValue();
            double r = FactorMath.logReturn(close, prev);
            out[i - 1] = r * r;
            prev = close;
        }
        return out;
    }

    private static int bars(Duration duration, long barMillis) {
        if (barMillis <= 0L) {
            barMillis = DEFAULT_BAR_MILLIS;
        }
        return Math.max(1, Math.toIntExact((duration.toMillis() + barMillis - 1L) / barMillis));
    }

    private static double mean(double[] values, int endExclusive, int window) {
        int start = Math.max(0, endExclusive - window);
        double sum = 0.0;
        int count = 0;
        for (int i = start; i < endExclusive; i++) {
            sum += values[i];
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static void accumulate(double[][] xtx, double[] xty, double[] x, double y) {
        for (int r = 0; r < COEFFICIENTS; r++) {
            xty[r] += x[r] * y;
            for (int c = 0; c < COEFFICIENTS; c++) {
                xtx[r][c] += x[r] * x[c];
            }
        }
    }

    private static double dot(double[] a, double[] b) {
        double out = 0.0;
        for (int i = 0; i < a.length; i++) out += a[i] * b[i];
        return out;
    }

    private double residualVariance(double[] rv, double[] beta) {
        double sumSq = 0.0;
        int n = 0;
        for (int target = longWindow; target < rv.length; target++) {
            double actual = Math.log(Math.max(rv[target], VAR_FLOOR));
            double fitted = dot(beta, featuresFor(rv, target));
            double e = actual - fitted;
            sumSq += e * e;
            n++;
        }
        int df = n - COEFFICIENTS;
        return df > 0 ? sumSq / df : 0.0;
    }

    /** 小矩阵高斯消元；病态时返回 null 走 fallback。 */
    private static double[] solve(double[][] a, double[] b) {
        double[][] m = new double[COEFFICIENTS][COEFFICIENTS + 1];
        for (int r = 0; r < COEFFICIENTS; r++) {
            System.arraycopy(a[r], 0, m[r], 0, COEFFICIENTS);
            m[r][COEFFICIENTS] = b[r];
        }

        for (int pivot = 0; pivot < COEFFICIENTS; pivot++) {
            int best = pivot;
            for (int r = pivot + 1; r < COEFFICIENTS; r++) {
                if (Math.abs(m[r][pivot]) > Math.abs(m[best][pivot])) best = r;
            }
            if (Math.abs(m[best][pivot]) < 1e-12) return null;
            double[] tmp = m[pivot];
            m[pivot] = m[best];
            m[best] = tmp;

            double scale = m[pivot][pivot];
            for (int c = pivot; c <= COEFFICIENTS; c++) m[pivot][c] /= scale;
            for (int r = 0; r < COEFFICIENTS; r++) {
                if (r == pivot) continue;
                double factor = m[r][pivot];
                for (int c = pivot; c <= COEFFICIENTS; c++) {
                    m[r][c] -= factor * m[pivot][c];
                }
            }
        }

        double[] x = new double[COEFFICIENTS];
        for (int r = 0; r < COEFFICIENTS; r++) x[r] = m[r][COEFFICIENTS];
        return x;
    }
}
