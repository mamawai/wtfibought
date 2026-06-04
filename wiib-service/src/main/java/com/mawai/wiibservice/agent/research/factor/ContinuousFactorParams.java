package com.mawai.wiibservice.agent.research.factor;

/** 连续因子窗口参数；单位都是调用方传入序列的 bar 数。 */
public record ContinuousFactorParams(
        int momentumLookback,
        int reversalLookback,
        int fundingLookback,
        int volumeLookback,
        int amihudLookback,
        int residualMomentumLookback,
        int betaWindow
) {
    /** 固定默认窗口，单位是评估后的 H-bar；只作为研究特征，不做网格寻优。 */
    public static ContinuousFactorParams defaults() {
        return new ContinuousFactorParams(12, 2, 20, 20, 20, 12, 20);
    }
}
