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
}
