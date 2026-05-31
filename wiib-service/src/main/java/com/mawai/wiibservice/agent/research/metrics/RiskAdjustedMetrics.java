package com.mawai.wiibservice.agent.research.metrics;

import java.math.BigDecimal;
import java.util.List;

/** 年化风险调整指标。 */
public record RiskAdjustedMetrics(
        double annualizedSharpe,
        double calmar,
        double maxDrawdown,
        double cagr,
        double hitRate,
        int periods
) {
    /**
     * 年化 Sharpe = (mean/sd)·sqrt(periodsPerYear)，sd 用样本方差(n-1)、rf=0（与现有口径一致）；
     * MaxDD 由复利净值峰谷；CAGR = equity^(periodsPerYear/n)-1；Calmar = CAGR/MaxDD；命中率=正收益占比。
     */
    public static RiskAdjustedMetrics from(ReturnSeries series) {
        List<BigDecimal> rs = series.periodReturns();
        int n = rs.size();
        if (n == 0) return new RiskAdjustedMetrics(0, 0, 0, 0, 0, 0);

        double[] r = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) {
            r[i] = rs.get(i).doubleValue();
            mean += r[i];
        }
        mean /= n;

        double var = 0;
        for (double x : r) var += (x - mean) * (x - mean);
        var = n > 1 ? var / (n - 1) : 0;
        double sd = Math.sqrt(var);
        double sharpe = sd == 0 ? 0 : (mean / sd) * Math.sqrt(series.periodsPerYear());

        double equity = 1.0, peak = 1.0, maxDD = 0.0;
        int wins = 0;
        for (double x : r) {
            equity *= (1 + x);
            if (equity > peak) peak = equity;
            double dd = peak == 0 ? 0 : (peak - equity) / peak;
            if (dd > maxDD) maxDD = dd;
            if (x > 0) wins++;
        }

        double years = (double) n / series.periodsPerYear();
        double cagr = years > 0 ? Math.pow(equity, 1.0 / years) - 1.0 : 0;
        double calmar = maxDD == 0 ? 0 : cagr / maxDD;
        double hitRate = (double) wins / n;

        return new RiskAdjustedMetrics(sharpe, calmar, maxDD, cagr, hitRate, n);
    }
}
