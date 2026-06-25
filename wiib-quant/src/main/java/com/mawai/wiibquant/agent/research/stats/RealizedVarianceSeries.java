package com.mawai.wiibquant.agent.research.stats;

import com.mawai.wiibquant.agent.research.factor.FactorMath;
import com.mawai.wiibcommon.market.KlineBar;

import java.util.List;

/**
 * horizon 已实现方差序列：把逐 bar 方差按"决策点之后 H 根"求和，得到每个决策点的 horizon 积分方差。
 * 比单根 (horizon收益)² 少噪得多（Andersen-Bollerslev RV）；GK 口径再用上 OHLC 极差，效率更高。
 * QLIKE 评估真值与 HAR-RV 输入都可用它。
 */
public final class RealizedVarianceSeries {

    private RealizedVarianceSeries() {
    }

    /** 逐 bar close-to-close 方差 r²（r=ln(close_j/close_{j-1})）；[0]=0（无前值）。 */
    public static double[] perBarCloseVariance(List<KlineBar> series) {
        int n = series.size();
        double[] out = new double[n];
        if (n < 2) return out;
        double prev = series.get(0).close().doubleValue();
        for (int i = 1; i < n; i++) {
            double c = series.get(i).close().doubleValue();
            double r = FactorMath.logReturn(c, prev);
            out[i] = r * r;
            prev = c;
        }
        return out;
    }

    /** 逐 bar Garman-Klass 方差（用该 bar 自身 OHLC）。 */
    public static double[] perBarGarmanKlass(List<KlineBar> series) {
        int n = series.size();
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            KlineBar b = series.get(i);
            out[i] = RangeVariance.garmanKlass(b.open().doubleValue(), b.high().doubleValue(),
                    b.low().doubleValue(), b.close().doubleValue());
        }
        return out;
    }

    /**
     * 每个决策点的 horizon 已实现方差 = Σ_{j=idx+1}^{idx+H} perBarVariance[j]（决策收盘之后的 H 根）。
     * 用前缀和 O(1)/点；idx=decisionBarIndexes[p]，H=horizonBars。
     */
    public static double[] horizonSums(double[] perBarVariance, List<Integer> decisionIdx, int horizonBars) {
        int n = perBarVariance.length;
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) {
            prefix[i + 1] = prefix[i] + perBarVariance[i];
        }
        double[] out = new double[decisionIdx.size()];
        for (int p = 0; p < decisionIdx.size(); p++) {
            int idx = decisionIdx.get(p);
            int from = Math.min(idx + 1, n);            // 决策当根不计入，从下一根起
            int to = Math.min(idx + horizonBars + 1, n); // 闭区间 [idx+1, idx+H] → 前缀差用开右端 idx+H+1
            out[p] = prefix[to] - prefix[from];
        }
        return out;
    }
}
