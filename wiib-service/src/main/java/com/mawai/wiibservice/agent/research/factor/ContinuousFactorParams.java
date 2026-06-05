package com.mawai.wiibservice.agent.research.factor;

import java.time.Duration;

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
    /** 默认按 5m 决策 bar 换算；只作为研究特征，不做网格寻优。 */
    public static ContinuousFactorParams defaults() {
        return forBarMillis(Duration.ofMinutes(5).toMillis());
    }

    /** 按真实时间长度换算成 5m/15m bar 数，避免 horizon 改动后窗口尺度漂移。 */
    public static ContinuousFactorParams forBarMillis(long barMillis) {
        return new ContinuousFactorParams(
                bars(Duration.ofHours(12), barMillis), // 半日动量
                bars(Duration.ofHours(1), barMillis),  // 短反转
                bars(Duration.ofHours(24), barMillis), // 资金费/量能/流动性日内上下文
                bars(Duration.ofHours(24), barMillis),
                bars(Duration.ofHours(24), barMillis),
                bars(Duration.ofHours(12), barMillis),
                bars(Duration.ofHours(48), barMillis)); // beta 稳一点，但不拉到多周
    }

    private static int bars(Duration duration, long barMillis) {
        if (duration == null || barMillis <= 0L) {
            throw new IllegalArgumentException("duration/barMillis 必须为正");
        }
        long millis = duration.toMillis();
        return Math.max(1, Math.toIntExact((millis + barMillis - 1L) / barMillis));
    }
}
