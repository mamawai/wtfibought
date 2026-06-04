package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.forecast.MarketRegime;

import java.util.Arrays;

/**
 * q 扫描特有的纯函数（test helper，非生产 API）：按 q 重建 regime 标签 + directionality 分位。
 * 混淆矩阵 / accuracy / balancedAccuracy / macroF1 一律复用生产
 * {@link com.mawai.wiibservice.agent.research.metrics.RegimeClassificationScore}（已 TDD），不在此重复。
 */
final class RegimeQScan {

    private RegimeQScan() {
    }

    /** 按 q 重建事后标签：SHOCK 优先；否则 directionality≥q → 按净收益符号定上/下；否则 RANGING。与 RegimeLabeler 同语义。 */
    static MarketRegime labelAt(double directionality, boolean shock, int netSign, double q) {
        if (shock) {
            return MarketRegime.SHOCK;
        }
        if (directionality >= q) {
            return netSign >= 0 ? MarketRegime.TRENDING_UP : MarketRegime.TRENDING_DOWN;
        }
        return MarketRegime.RANGING;
    }

    /** 分位（type-7 线性插值，p∈[0,1]）：空→NaN，单元素→该值。用于据真实 directionality 分布选 q。 */
    static double percentile(double[] values, double p) {
        if (values.length == 0) return Double.NaN;
        double[] s = values.clone();
        Arrays.sort(s);
        if (s.length == 1) return s[0];
        double rank = p * (s.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        double frac = rank - lo;
        return s[lo] + frac * (s[hi] - s[lo]);
    }
}
