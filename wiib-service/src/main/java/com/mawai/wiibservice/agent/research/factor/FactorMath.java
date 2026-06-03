package com.mawai.wiibservice.agent.research.factor;

/**
 * 连续因子构造的基础数学原语：纯函数、无 DB、无状态。
 * 调用方负责传入 point-in-time 的窗口；这里不做时间裁剪，避免把时序语义藏进工具函数。
 */
public final class FactorMath {

    private FactorMath() {
    }

    /** 当前值相对窗口的 z-score；样本不足或窗口无波动时返回中性 0。 */
    public static double zScore(double current, double[] window) {
        int n = size(window);
        if (n < 2) return 0.0;
        double mean = mean(window, n);
        double sd = sampleStd(window, n, mean);
        return sd == 0.0 ? 0.0 : (current - mean) / sd;
    }

    /**
     * 当前值在窗口中的中位秩分位，范围 [0,1]。
     * ties 用 0.5 个权重，避免常数窗口被误判成 100% 分位。
     */
    public static double percentileRank(double current, double[] window) {
        int n = size(window);
        if (n == 0) return 0.5;
        int less = 0;
        int equal = 0;
        for (double v : window) {
            if (v < current) {
                less++;
            } else if (v == current) {
                equal++;
            }
        }
        return (less + 0.5 * equal) / n;
    }

    /** 简单变化率：last / lagged - 1。样本不足或分母为 0 时返回中性 0。 */
    public static double rateOfChange(double[] values, int lag) {
        int n = size(values);
        if (lag <= 0 || n <= lag) return 0.0;
        double previous = values[n - 1 - lag];
        return previous == 0.0 ? 0.0 : values[n - 1] / previous - 1.0;
    }

    /** 对数收益 ln(current / previous)；价格非正时返回中性 0。 */
    public static double logReturn(double current, double previous) {
        if (current <= 0.0 || previous <= 0.0) return 0.0;
        return Math.log(current / previous);
    }

    /** 把值截到 [lower, upper]，用于 winsorize 后的单点收口。 */
    public static double winsorize(double value, double lower, double upper) {
        if (value < lower) return lower;
        if (value > upper) return upper;
        return value;
    }

    /** tanh 有界压缩，输出 [-1,1]；scale 控制收缩速度。 */
    public static double squash(double value, double scale) {
        if (scale <= 0.0) return Math.tanh(value);
        return Math.tanh(value / scale);
    }

    /** 窗口样本波动率；样本不足返回 0。 */
    public static double volatility(double[] returns) {
        int n = size(returns);
        if (n < 2) return 0.0;
        return sampleStd(returns, n, mean(returns, n));
    }

    private static int size(double[] values) {
        return values == null ? 0 : values.length;
    }

    private static double mean(double[] values, int n) {
        double sum = 0.0;
        for (double v : values) sum += v;
        return sum / n;
    }

    private static double sampleStd(double[] values, int n, double mean) {
        double ss = 0.0;
        for (double v : values) {
            double d = v - mean;
            ss += d * d;
        }
        return Math.sqrt(ss / (n - 1));
    }
}
