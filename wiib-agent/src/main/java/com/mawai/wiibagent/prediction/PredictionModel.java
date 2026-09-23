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
}
