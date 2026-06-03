package com.mawai.wiibservice.agent.research.factor;

import java.util.Arrays;

/**
 * 连续因子信号构造：纯函数、point-in-time。
 * endExclusive 表示当前决策点能看到的序列右边界；函数只读 [0,endExclusive)，不读未来。
 */
public final class ContinuousFactorSignals {

    private ContinuousFactorSignals() {
    }

    /** 风险调整动量：lookback 期累计 log return / 同窗收益波动。 */
    public static double riskAdjustedMomentum(double[] closes, int endExclusive, int lookback) {
        int end = Math.min(size(closes), endExclusive);
        if (lookback <= 0 || end <= lookback) return 0.0;
        double momentum = FactorMath.logReturn(closes[end - 1], closes[end - 1 - lookback]);
        double vol = logReturnVolatility(closes, end, lookback);
        return vol == 0.0 ? 0.0 : momentum / vol;
    }

    /** 短期反转：短窗动量取反。 */
    public static double shortReversal(double[] closes, int endExclusive, int lookback) {
        return -riskAdjustedMomentum(closes, endExclusive, lookback);
    }

    /** funding carry：资金费越高，多头越拥挤，long 方向得分越低。 */
    public static double fundingCarry(double[] fundingRates, int endExclusive, int lookback) {
        int end = Math.min(size(fundingRates), endExclusive);
        if (lookback <= 0 || end == 0) return 0.0;
        double[] window = tailWindow(fundingRates, end, lookback);
        return -FactorMath.zScore(fundingRates[end - 1], window);
    }

    /** 最新成交量相对自身历史窗口的 z-score。 */
    public static double volumeZScore(double[] volumes, int endExclusive, int lookback) {
        int end = Math.min(size(volumes), endExclusive);
        if (lookback <= 0 || end == 0) return 0.0;
        double[] window = tailWindow(volumes, end, lookback);
        return FactorMath.zScore(volumes[end - 1], window);
    }

    /**
     * Amihud 非流动性：mean(|r_t| / dollarVolume_t)。
     * dollarVolume 用 close_t * volume_t 近似；返回值越高表示越不流动。
     */
    public static double amihudIlliquidity(double[] closes, double[] volumes, int endExclusive, int lookback) {
        int end = Math.min(Math.min(size(closes), size(volumes)), endExclusive);
        if (lookback <= 0 || end < 2) return 0.0;
        int start = Math.max(1, end - lookback);
        double sum = 0.0;
        int count = 0;
        for (int i = start; i < end; i++) {
            double dollarVolume = closes[i] * volumes[i];
            if (dollarVolume <= 0.0) continue;
            sum += Math.abs(FactorMath.logReturn(closes[i], closes[i - 1])) / dollarVolume;
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    /**
     * 残差动量：最近 lookback 期 residual return 求和。
     * 每一期 residual 的 beta 只用该期之前的 betaWindow 历史估计。
     */
    public static double residualMomentum(double[] assetReturns, double[] benchmarkReturns,
                                          int endExclusive, int lookback, int betaWindow) {
        int end = Math.min(Math.min(size(assetReturns), size(benchmarkReturns)), endExclusive);
        if (lookback <= 0 || end <= 0) return 0.0;
        int start = Math.max(0, end - lookback);
        double sum = 0.0;
        for (int i = start; i < end; i++) {
            sum += RollingRegression.residualAt(assetReturns, benchmarkReturns, i, betaWindow);
        }
        return sum;
    }

    private static double logReturnVolatility(double[] closes, int end, int lookback) {
        if (end <= lookback) return 0.0;
        double[] returns = new double[lookback];
        int start = end - lookback;
        for (int i = start; i < end; i++) {
            returns[i - start] = FactorMath.logReturn(closes[i], closes[i - 1]);
        }
        return FactorMath.volatility(returns);
    }

    private static double[] tailWindow(double[] values, int end, int lookback) {
        int start = Math.max(0, end - lookback);
        return Arrays.copyOfRange(values, start, end);
    }

    private static int size(double[] values) {
        return values == null ? 0 : values.length;
    }
}
