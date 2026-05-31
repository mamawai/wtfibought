package com.mawai.wiibservice.agent.research.stats;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.List;

/** 对数收益的 EWMA 波动率（每 bar 的分数波动率）。 */
public final class VolatilityEstimator {

    private VolatilityEstimator() {
    }

    /**
     * var_t = lambda·var_{t-1} + (1-lambda)·r_t²，r_t = ln(close_t / close_{t-1})；
     * 首个收益用其平方做种子。返回 sqrt(var)。lambda 典型 0.94（RiskMetrics）。
     */
    public static double ewmaVolatility(List<KlineBar> bars, double lambda) {
        if (bars == null || bars.size() < 2) return 0.0;
        double var = 0.0;
        boolean seeded = false;
        double prevClose = bars.get(0).close().doubleValue();
        for (int i = 1; i < bars.size(); i++) {
            double c = bars.get(i).close().doubleValue();
            double r = Math.log(c / prevClose);
            prevClose = c;
            if (!seeded) {
                var = r * r;
                seeded = true;
            } else {
                var = lambda * var + (1 - lambda) * r * r;
            }
        }
        return Math.sqrt(var);
    }
}
