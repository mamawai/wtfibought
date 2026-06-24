package com.mawai.wiibservice.agent.research.factor;

/** 把已对齐的 point-in-time 序列装配成一个连续因子向量。 */
public final class ContinuousFactorBuilder {

    private ContinuousFactorBuilder() {
    }

    /**
     * endExclusive 是决策点右边界；所有输入序列只读取 [0,endExclusive)。
     * closes/volumes/fundingRates/returns 均需由调用方提前按同一时间轴对齐。
     */
    public static ContinuousFactorVector build(double[] closes,
                                               double[] volumes,
                                               double[] fundingRates,
                                               double[] assetReturns,
                                               double[] benchmarkReturns,
                                               int endExclusive,
                                               ContinuousFactorParams params) {
        return new ContinuousFactorVector(
                ContinuousFactorSignals.riskAdjustedMomentum(closes, endExclusive, params.momentumLookback()),
                ContinuousFactorSignals.shortReversal(closes, endExclusive, params.reversalLookback()),
                ContinuousFactorSignals.fundingCarry(fundingRates, endExclusive, params.fundingLookback()),
                ContinuousFactorSignals.volumeZScore(volumes, endExclusive, params.volumeLookback()),
                ContinuousFactorSignals.amihudIlliquidity(closes, volumes, endExclusive, params.amihudLookback()),
                ContinuousFactorSignals.residualMomentum(assetReturns, benchmarkReturns, endExclusive,
                        params.residualMomentumLookback(), params.betaWindow())
        );
    }

    /**
     * 批量构造所有 endExclusive 的连续因子。
     * 常用腿用前缀和从 O(N·W) 降到 O(N)；有 benchmark 的 residual 腿先沿用旧口径逐点算，保证不偷改语义。
     */
    public static ContinuousFactorVector[] buildSeries(double[] closes,
                                                       double[] volumes,
                                                       double[] fundingRates,
                                                       double[] assetReturns,
                                                       double[] benchmarkReturns,
                                                       ContinuousFactorParams params) {
        int n = size(closes);
        ContinuousFactorVector[] out = new ContinuousFactorVector[n + 1];
        if (n == 0) {
            out[0] = ContinuousFactorVector.neutral();
            return out;
        }

        double[] closeReturns = logReturns(closes);
        double[] returnPrefix = prefix(closeReturns);
        double[] returnSqPrefix = prefixSquares(closeReturns);
        double[] fundingPrefix = prefix(fundingRates);
        double[] fundingSqPrefix = prefixSquares(fundingRates);
        double[] volumePrefix = prefix(volumes);
        double[] volumeSqPrefix = prefixSquares(volumes);
        AmihudPrefix amihud = amihudPrefix(closes, volumes);
        boolean hasBenchmark = benchmarkReturns != null && benchmarkReturns.length > 0;

        for (int end = 0; end <= n; end++) {
            double ram = riskAdjustedMomentum(closes, returnPrefix, returnSqPrefix, end, params.momentumLookback());
            double reversal = -riskAdjustedMomentum(closes, returnPrefix, returnSqPrefix, end, params.reversalLookback());
            double fundingCarry = -zScoreAt(fundingRates, fundingPrefix, fundingSqPrefix, end, params.fundingLookback());
            double volumeZ = zScoreAt(volumes, volumePrefix, volumeSqPrefix, end, params.volumeLookback());
            double amihudValue = amihudMean(amihud, end, params.amihudLookback());
            double residual = hasBenchmark
                    ? ContinuousFactorSignals.residualMomentum(assetReturns, benchmarkReturns, end,
                    params.residualMomentumLookback(), params.betaWindow())
                    : 0.0;
            out[end] = new ContinuousFactorVector(ram, reversal, fundingCarry, volumeZ, amihudValue, residual);
        }
        return out;
    }

    private record AmihudPrefix(double[] sum, int[] count) {
    }

    private static double riskAdjustedMomentum(double[] closes, double[] returnPrefix, double[] returnSqPrefix,
                                               int endExclusive, int lookback) {
        int end = Math.min(size(closes), endExclusive);
        if (lookback <= 0 || end <= lookback) return 0.0;
        double momentum = FactorMath.logReturn(closes[end - 1], closes[end - 1 - lookback]);
        double vol = sampleStd(returnPrefix, returnSqPrefix, end - lookback, end);
        return vol == 0.0 ? 0.0 : momentum / vol;
    }

    private static double zScoreAt(double[] values, double[] prefix, double[] sqPrefix, int endExclusive, int lookback) {
        int end = Math.min(size(values), endExclusive);
        if (lookback <= 0 || end == 0) return 0.0;
        int start = Math.max(0, end - lookback);
        double sd = sampleStd(prefix, sqPrefix, start, end);
        if (sd == 0.0) return 0.0;
        double mean = sum(prefix, start, end) / (end - start);
        return (values[end - 1] - mean) / sd;
    }

    private static AmihudPrefix amihudPrefix(double[] closes, double[] volumes) {
        int n = Math.min(size(closes), size(volumes));
        double[] sum = new double[n + 1];
        int[] count = new int[n + 1];
        for (int i = 1; i < n; i++) {
            sum[i + 1] = sum[i];
            count[i + 1] = count[i];
            double dollarVolume = closes[i] * volumes[i];
            if (dollarVolume > 0.0) {
                sum[i + 1] += Math.abs(FactorMath.logReturn(closes[i], closes[i - 1])) / dollarVolume;
                count[i + 1]++;
            }
        }
        return new AmihudPrefix(sum, count);
    }

    private static double amihudMean(AmihudPrefix prefix, int endExclusive, int lookback) {
        int end = Math.min(prefix.sum().length - 1, endExclusive);
        if (lookback <= 0 || end < 2) return 0.0;
        int start = Math.max(1, end - lookback);
        int count = prefix.count()[end] - prefix.count()[start];
        return count == 0 ? 0.0 : (prefix.sum()[end] - prefix.sum()[start]) / count;
    }

    private static double[] prefix(double[] values) {
        int n = size(values);
        double[] out = new double[n + 1];
        for (int i = 0; i < n; i++) out[i + 1] = out[i] + finite(values[i]);
        return out;
    }

    private static double[] logReturns(double[] closes) {
        int n = size(closes);
        double[] out = new double[n];
        for (int i = 1; i < n; i++) {
            out[i] = FactorMath.logReturn(closes[i], closes[i - 1]);
        }
        return out;
    }

    private static double[] prefixSquares(double[] values) {
        int n = size(values);
        double[] out = new double[n + 1];
        for (int i = 0; i < n; i++) {
            double v = finite(values[i]);
            out[i + 1] = out[i] + v * v;
        }
        return out;
    }

    private static double sampleStd(double[] prefix, double[] sqPrefix, int startInclusive, int endExclusive) {
        int n = endExclusive - startInclusive;
        if (n < 2) return 0.0;
        double sum = sum(prefix, startInclusive, endExclusive);
        double sumSq = sum(sqPrefix, startInclusive, endExclusive);
        double var = (sumSq - sum * sum / n) / (n - 1);
        return var <= 0.0 ? 0.0 : Math.sqrt(var);
    }

    private static double sum(double[] prefix, int startInclusive, int endExclusive) {
        int start = Math.max(0, Math.min(startInclusive, prefix.length - 1));
        int end = Math.max(start, Math.min(endExclusive, prefix.length - 1));
        return prefix[end] - prefix[start];
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static int size(double[] values) {
        return values == null ? 0 : values.length;
    }
}
