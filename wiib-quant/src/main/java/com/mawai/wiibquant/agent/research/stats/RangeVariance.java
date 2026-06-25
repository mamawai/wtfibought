package com.mawai.wiibquant.agent.research.stats;

/** 区间(OHLC)波动率估计：用高低开收的极差信息，统计效率远高于纯 close-to-close r²。 */
public final class RangeVariance {

    private static final double TWO_LN2_MINUS_1 = 2.0 * Math.log(2.0) - 1.0; // ≈0.386294

    private RangeVariance() {
    }

    /**
     * Garman-Klass 单期方差估计：0.5·(ln(H/L))² − (2ln2−1)·(ln(C/O))²。
     * 极差项贡献绝大部分信息，开收项做漂移修正；理论效率 ≈ close-to-close r² 的 7 倍。
     * 任一价格非正 → 0（真实 K 线价格恒正，这是兜底防对数发散）。
     */
    public static double garmanKlass(double open, double high, double low, double close) {
        if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) {
            return 0.0;
        }
        double lnHL = Math.log(high / low);
        double lnCO = Math.log(close / open);
        return 0.5 * lnHL * lnHL - TWO_LN2_MINUS_1 * lnCO * lnCO;
    }
}
