package com.mawai.wiibservice.agent.research.factor;

/**
 * 横截面因子会频繁用到的 rolling beta / residual 原语。
 * 约定 y=目标资产收益，x=基准收益（如 BTC）；所有窗口都由调用方传入已按时间升序的 point-in-time 序列。
 */
public final class RollingRegression {

    private RollingRegression() {
    }

    /** 用尾部 window 个点估 beta；等价于 beta(y, x, minLen, window)。 */
    public static double beta(double[] y, double[] x, int window) {
        return beta(y, x, minLength(y, x), window);
    }

    /** 用 [max(0,end-window), end) 估 beta；样本不足或基准无波动时返回 0。 */
    public static double beta(double[] y, double[] x, int endExclusive, int window) {
        int end = Math.min(endExclusive, minLength(y, x));
        if (window <= 0 || end < 2) return 0.0;
        int start = Math.max(0, end - window);
        int n = end - start;
        if (n < 2) return 0.0;

        double meanX = 0.0;
        double meanY = 0.0;
        for (int i = start; i < end; i++) {
            meanX += x[i];
            meanY += y[i];
        }
        meanX /= n;
        meanY /= n;

        double cov = 0.0;
        double varX = 0.0;
        for (int i = start; i < end; i++) {
            double dx = x[i] - meanX;
            cov += dx * (y[i] - meanY);
            varX += dx * dx;
        }
        return varX == 0.0 ? 0.0 : cov / varX;
    }

    /** 单点残差：y - beta * x。 */
    public static double residual(double y, double x, double beta) {
        return y - beta * x;
    }

    /**
     * index 当期残差；beta 只用 index 之前的历史估计。
     * 这是给因子构造用的无前视口径：当前收益参与残差本身，但不参与 beta 拟合。
     */
    public static double residualAt(double[] y, double[] x, int index, int betaWindow) {
        if (y == null || x == null || index < 0 || index >= y.length || index >= x.length) return 0.0;
        double b = beta(y, x, index, betaWindow);
        return residual(y[index], x[index], b);
    }

    /** 最新一期残差；beta 用最新一期之前的历史估计。 */
    public static double latestResidual(double[] y, double[] x, int betaWindow) {
        int n = minLength(y, x);
        return n == 0 ? 0.0 : residualAt(y, x, n - 1, betaWindow);
    }

    private static int minLength(double[] y, double[] x) {
        if (y == null || x == null) return 0;
        return Math.min(y.length, x.length);
    }
}
