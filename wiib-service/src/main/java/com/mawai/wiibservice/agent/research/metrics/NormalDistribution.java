package com.mawai.wiibservice.agent.research.metrics;

/**
 * 标准正态分布 CDF Φ / 逆 CDF Φ⁻¹（自实现，无外部依赖——spec §4 铁律）。
 * DSR/PSR 全靠这两个函数：Φ 把 z 值转概率，Φ⁻¹ 给"试 N 次纯运气的期望最大 Sharpe"取分位。
 */
public final class NormalDistribution {

    private static final double SQRT2 = Math.sqrt(2.0);
    private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);

    // Acklam Φ⁻¹ 有理逼近系数（相对误差 <1.15e-9）。
    private static final double A1 = -3.969683028665376e+01, A2 = 2.209460984245205e+02,
            A3 = -2.759285104469687e+02, A4 = 1.383577518672690e+02,
            A5 = -3.066479806614716e+01, A6 = 2.506628277459239e+00;
    private static final double B1 = -5.447609879822406e+01, B2 = 1.615858368580409e+02,
            B3 = -1.556989798598866e+02, B4 = 6.680131188771972e+01, B5 = -1.328068155288572e+01;
    private static final double C1 = -7.784894002430293e-03, C2 = -3.223964580411365e-01,
            C3 = -2.400758277161838e+00, C4 = -2.549732539343734e+00,
            C5 = 4.374664141464968e+00, C6 = 2.938163982698783e+00;
    private static final double D1 = 7.784695709041462e-03, D2 = 3.224671290700398e-01,
            D3 = 2.445134137142996e+00, D4 = 3.754408661907416e+00;
    private static final double P_LOW = 0.02425, P_HIGH = 1.0 - P_LOW; // 三段分界

    private NormalDistribution() {
    }

    /** Φ(x)=P(Z≤x)。erf 用 Abramowitz-Stegun 7.1.26（|误差|≤1.5e-7）。 */
    public static double cdf(double x) {
        return 0.5 * (1.0 + erf(x / SQRT2));
    }

    private static double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t;
        double y = 1.0 - poly * Math.exp(-x * x);
        return Math.signum(x) * y;              // signum(0)=0 → erf(0)=0 → Φ(0)=0.5 精确
    }

    /** Φ⁻¹(p)：Acklam 三段有理逼近 + 一步 Halley（用自家 cdf 把残差收紧到机器精度级）。 */
    public static double invCdf(double p) {
        if (p <= 0.0) return Double.NEGATIVE_INFINITY;
        if (p >= 1.0) return Double.POSITIVE_INFINITY;

        double x;
        if (p < P_LOW) {                         // 左尾
            double q = Math.sqrt(-2.0 * Math.log(p));
            x = (((((C1 * q + C2) * q + C3) * q + C4) * q + C5) * q + C6)
                    / ((((D1 * q + D2) * q + D3) * q + D4) * q + 1.0);
        } else if (p <= P_HIGH) {                // 中段
            double q = p - 0.5;
            double r = q * q;
            x = (((((A1 * r + A2) * r + A3) * r + A4) * r + A5) * r + A6) * q
                    / (((((B1 * r + B2) * r + B3) * r + B4) * r + B5) * r + 1.0);
        } else {                                 // 右尾
            double q = Math.sqrt(-2.0 * Math.log(1.0 - p));
            x = -(((((C1 * q + C2) * q + C3) * q + C4) * q + C5) * q + C6)
                    / ((((D1 * q + D2) * q + D3) * q + D4) * q + 1.0);
        }

        double e = cdf(x) - p;                    // Halley 一步：残差 e、按 pdf 反推步长
        double u = e * SQRT_2PI * Math.exp(x * x / 2.0);
        return x - u / (1.0 + x * u / 2.0);
    }
}
