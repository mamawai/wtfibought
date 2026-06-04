package com.mawai.wiibservice.agent.research.factor;

/** 单个 symbol 在单个决策点的连续因子向量；只存数值，不存交易方向。 */
public record ContinuousFactorVector(
        double riskAdjustedMomentum,
        double shortReversal,
        double fundingCarry,
        double volumeZScore,
        double amihudIlliquidity,
        double residualMomentum
) {
    public ContinuousFactorVector {
        riskAdjustedMomentum = normalizeSignedZero(riskAdjustedMomentum);
        shortReversal = normalizeSignedZero(shortReversal);
        fundingCarry = normalizeSignedZero(fundingCarry);
        volumeZScore = normalizeSignedZero(volumeZScore);
        amihudIlliquidity = normalizeSignedZero(amihudIlliquidity);
        residualMomentum = normalizeSignedZero(residualMomentum);
    }

    private static double normalizeSignedZero(double value) {
        return value == 0.0 ? 0.0 : value;
    }

    public static ContinuousFactorVector neutral() {
        return new ContinuousFactorVector(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }
}
