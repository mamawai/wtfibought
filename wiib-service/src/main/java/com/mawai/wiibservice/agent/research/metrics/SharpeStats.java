package com.mawai.wiibservice.agent.research.metrics;

/**
 * 逐期收益的 per-period 矩：Sharpe（mean/样本sd，rf=0）+ skewness + kurtosis（Pearson，正态=3）。
 * 全是"每期"口径、未年化——DSR/PSR 的方差校正项 √(1−skew·SR+((kurt−1)/4)SR²) 正需要这套。
 * 口径：SR 的 sd 用样本(n-1)；skew/kurt 标准化用总体 σ(÷n) 的有偏矩（等价 scipy 默认 bias=True / fisher=False）。
 */
public record SharpeStats(double sharpe, double skewness, double kurtosis, int n) {

    public static SharpeStats of(double[] returns) {
        int n = returns == null ? 0 : returns.length;
        if (n < 2) return new SharpeStats(0.0, 0.0, 0.0, n); // 无样本方差 → 退化

        double mean = 0;
        for (double r : returns) mean += r;
        mean /= n;

        double m2 = 0, m3 = 0, m4 = 0;                 // 2/3/4 阶中心矩之和（÷n 在后）
        for (double r : returns) {
            double d = r - mean;
            double d2 = d * d;
            m2 += d2;
            m3 += d2 * d;
            m4 += d2 * d2;
        }

        double sampleStd = Math.sqrt(m2 / (n - 1));     // 样本 sd → Sharpe
        double sharpe = sampleStd == 0 ? 0.0 : mean / sampleStd;

        double popVar = m2 / n;                          // 总体方差 → 标准化高阶矩
        double popStd = Math.sqrt(popVar);
        if (popStd == 0) return new SharpeStats(sharpe, 0.0, 0.0, n); // 常数序列

        double skew = (m3 / n) / (popVar * popStd);      // m3 / σ³
        double kurt = (m4 / n) / (popVar * popVar);      // m4 / σ⁴（Pearson）
        return new SharpeStats(sharpe, skew, kurt, n);
    }
}
