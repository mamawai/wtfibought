package com.mawai.wiibquant.agent.research.metrics;

/**
 * Deflated Sharpe Ratio（Bailey & López de Prado 2014, [S9]）：试了 N 组配置后，
 * 把观测 Sharpe 扣掉"纯运气的期望最大 Sharpe"SR0、再按样本长度与非正态（skew/kurt）校正，
 * 输出"真 Sharpe>0 的概率"。DSR≥阈值（如 0.95）才算扛得住多重检验选择偏差。
 * 全程 per-period（未年化）口径，与 {@link SharpeStats} 一致。
 */
public final class DeflatedSharpe {

    private static final double EULER = 0.5772156649015329; // 欧拉-马歇罗尼常数

    private DeflatedSharpe() {
    }

    /**
     * SR0：N 次独立试验下"纯运气"能达到的期望最大 Sharpe（per-period）。
     * = √V·[(1−γ)·Φ⁻¹(1−1/N) + γ·Φ⁻¹(1−1/(N·e))]，V=各试验 Sharpe 的方差。
     * N<2 或 V≤0 → 0：无可扣，DSR 退化为对 0 的 PSR。
     */
    public static double expectedMaxSharpe(double varianceOfSharpes, int nTrials) {
        if (nTrials < 2 || varianceOfSharpes <= 0) return 0.0;
        double sd = Math.sqrt(varianceOfSharpes);
        double a = NormalDistribution.invCdf(1.0 - 1.0 / nTrials);
        double b = NormalDistribution.invCdf(1.0 - 1.0 / (nTrials * Math.E));
        return sd * ((1.0 - EULER) * a + EULER * b);
    }

    /**
     * DSR = PSR(基准=benchmarkSharpe)：P(真 Sharpe > benchmark)。
     * = Φ[ (SR−benchmark)·√(T−1) / √(1 − skew·SR + ((kurt−1)/4)·SR²) ]。
     * 多重检验校正即取 benchmark=SR0。T<2 → 0；分母平方≤0（极端矩病态）→ NaN 交调用方处理。
     */
    public static double deflatedSharpe(SharpeStats stats, double benchmarkSharpe) {
        int t = stats.n();
        if (t < 2) return 0.0;
        double sr = stats.sharpe();
        double denomSq = 1.0 - stats.skewness() * sr + ((stats.kurtosis() - 1.0) / 4.0) * sr * sr;
        if (denomSq <= 0) return Double.NaN;
        double z = (sr - benchmarkSharpe) * Math.sqrt(t - 1.0) / Math.sqrt(denomSq);
        return NormalDistribution.cdf(z);
    }

    /** 各试验 per-period Sharpe 的样本方差(n-1)，喂给 {@link #expectedMaxSharpe}。 */
    public static double varianceOfSharpes(SharpeStats[] trials) {
        int k = trials == null ? 0 : trials.length;
        if (k < 2) return 0.0;
        double mean = 0;
        for (SharpeStats s : trials) mean += s.sharpe();
        mean /= k;
        double ss = 0;
        for (SharpeStats s : trials) {
            double d = s.sharpe() - mean;
            ss += d * d;
        }
        return ss / (k - 1);
    }
}
