package com.mawai.wiibquant.agent.research.stats;

import com.mawai.wiibquant.agent.research.factor.FactorMath;
import com.mawai.wiibcommon.market.KlineBar;

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
            double r = FactorMath.logReturn(c, prevClose);
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

    /**
     * 与 {@link #ewmaVolatility(List, double)} 同口径，但直接吃预先对齐的 log return 数组。
     * returns[i] 表示 close[i]/close[i-1]，所以窗口 [barStart, endExclusive) 对应 return 索引 [barStart+1, endExclusive)。
     */
    public static double ewmaVolatility(double[] returns, int endExclusive, int lookbackBars, double lambda) {
        if (returns == null || endExclusive < 2 || lookbackBars < 2) return 0.0;
        int end = Math.min(endExclusive, returns.length);
        int start = Math.max(1, end - lookbackBars + 1);
        if (start >= end) return 0.0;
        double var = 0.0;
        boolean seeded = false;
        for (int i = start; i < end; i++) {
            double r = returns[i];
            if (!Double.isFinite(r)) {
                r = 0.0;
            }
            if (!seeded) {
                var = r * r;
                seeded = true;
            } else {
                var = lambda * var + (1 - lambda) * r * r;
            }
        }
        return Math.sqrt(var);
    }

    /**
     * 已实现波动率（horizon 口径）= sqrt(Σ rᵢ²)，rᵢ = ln(close_i / close_{i-1})。
     * 与 {@link #ewmaVolatility}（per-bar 分数波动率）不同：这里把整段路径的逐 bar 平方收益累加再开方，
     * 量纲 = 该路径所跨越「整段」的波动，可与单期 baseline σ 同口径比较（如 RegimeLabeler 的 SHOCK 判定）。
     */
    public static double realizedVolatility(List<KlineBar> bars) {
        if (bars == null || bars.size() < 2) return 0.0;
        double sumSq = 0.0;
        double prevClose = bars.get(0).close().doubleValue();
        for (int i = 1; i < bars.size(); i++) {
            double c = bars.get(i).close().doubleValue();
            double r = FactorMath.logReturn(c, prevClose);
            prevClose = c;
            sumSq += r * r;
        }
        return Math.sqrt(sumSq);
    }

}
