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

    /**
     * 滚动已实现 σ（per-bar 口径）= sqrt(mean(最后 window 个平方对数收益))，零均值假设。
     * window ≥ 可用收益数时退化为全样本——故 window=Integer.MAX_VALUE 即"常数/扩张 σ"（climatology）。
     * 与 {@link #ewmaVolatility} 同 per-bar stdev 口径但等权、且平滑不趋零，作 vol 预测的公平基准
     * （对照 random-walk「单个上期 |r|」会频繁塌到 0 致 QLIKE 爆炸）；与 {@link #realizedVolatility}（整段求和）口径不同。
     */
    public static double rollingSigma(List<KlineBar> bars, int window) {
        if (bars == null || bars.size() < 2 || window < 1) return 0.0;
        int use = Math.min(window, bars.size() - 1); // 可用收益数 = bars 数 − 1
        double sumSq = 0.0;
        double prevClose = bars.get(bars.size() - use - 1).close().doubleValue();
        for (int i = bars.size() - use; i < bars.size(); i++) {
            double c = bars.get(i).close().doubleValue();
            double r = FactorMath.logReturn(c, prevClose);
            prevClose = c;
            sumSq += r * r;
        }
        return Math.sqrt(sumSq / use);
    }
}
