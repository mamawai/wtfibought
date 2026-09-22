package com.mawai.wiibagent.prediction;

import com.mawai.wiibquant.research.metrics.NormalDistribution;

/**
 * 公平概率。UP 份额就是"末分钟均价 ≥ 开盘均价"这张数字期权，价只由领先幅度、剩余时间、波动决定。
 * 价格当零漂移随机游走：进末分钟前，末分钟均价的噪声 = σ² × (到末分钟的秒数 + 60/3)；进了末分钟，
 * 已走过的部分锁死，只剩余下几秒的噪声。开盘基准是开盘时刻的 60 秒 TWAP（官方口径）。
 * "末分钟"是收盘前 63 到 3 秒：官方 TWAP 流在 T 时刻的值 = 截至 T−3 秒的 60 个整秒现货价的均价。
 */
final class PredictionModel {

    static final int TWAP_SECONDS = 60;
    /** 结算均价在收盘前这么多秒截止 */
    static final int SETTLE_LAG_SECONDS = 3;
    /** 倾斜前把概率夹住：Φ⁻¹ 在 0 和 1 处是无穷 */
    private static final double P_CLAMP = 0.01;

    private PredictionModel() {
    }

    /**
     * 纯数学的 z，正 = 偏 UP。
     *
     * @param leadPct      现价相对开盘均价（%）
     * @param twapSoFarPct 末分钟已走过部分的均价相对开盘均价（%）；还没进末分钟为 null
     * @param sigma1mPct   1 分钟典型波动（%）
     * @param r            离结算均价截止的秒数
     */
    static double z(double leadPct, Double twapSoFarPct, double sigma1mPct, double r) {
        double sigmaSec = sigma1mPct / Math.sqrt(60.0);
        if (r >= TWAP_SECONDS) {
            double sigma = sigmaSec * Math.sqrt((r - TWAP_SECONDS) + TWAP_SECONDS / 3.0);
            return leadPct / sigma;
        }
        // 末分钟：已走过 elapsed 秒的均价按权重锁定，余下 r 秒的均价期望是现价、噪声 σ² r³ / (3 × 60²)
        double elapsed = TWAP_SECONDS - r;
        double mean = (elapsed * twapSoFarPct + r * leadPct) / TWAP_SECONDS;
        double sigma = sigmaSec * Math.pow(r, 1.5) / (TWAP_SECONDS * Math.sqrt(3.0));
        return mean / sigma;
    }

    static double p(double z) {
        return NormalDistribution.cdf(z);
    }

    /**
     * 后劲修正后的概率，只用来记分：看"这一波还有后劲吗"这个判断本身有没有信息量，不参与买卖。
     *
     * @param momentum  还在推的概率减在回吐的概率，−1 … +1
     * @param driftSign 开盘以来方向，平盘 0 时不动
     * @param tilt      momentum 满格时加在 z 上的量
     */
    static double tilted(double p, double momentum, int driftSign, double tilt) {
        double clamped = Math.clamp(p, P_CLAMP, 1 - P_CLAMP);
        return p(inverse(clamped) + tilt * momentum * driftSign);
    }

    /** Φ⁻¹：Acklam 有理逼近，相对误差 1e-9 量级，p 须在 (0,1) */
    static double inverse(double p) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double low = 0.02425;
        if (p < low) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - low) {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }
}
