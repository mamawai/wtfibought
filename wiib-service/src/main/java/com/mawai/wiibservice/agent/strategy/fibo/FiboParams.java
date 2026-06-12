package com.mawai.wiibservice.agent.strategy.fibo;

/**
 * fibo 策略参数组（方向无关 limit 回踩范式）；回测扫参时整组替换，避免散落魔法常量。
 *
 * <p>入场在 entryFib(默认 0.66 黄金口袋深端)挂限价单等回踩，不做方向过滤；止损固定 slFibRatio(0.88)位+ATR缓冲。
 * swingLookbackBars 既是找腿回看根数，也是 armed 腿龄上限（腿终点滑出窗口即作废）。
 * orderTimeoutBars 是挂单超时(5m 根数)：挂单后这么多根未成交即撤。
 * 止盈(scale-out)：浮盈达 scaleOutAtR×R 落袋 scaleOutFraction 仓位，剩余仓拉到延伸位 TP；
 * 默认 fraction=1.0=全仓在 0.4R 落袋——经 BTC/ETH/SOL 三币六年样本外验证的短线均值回归最优几何。</p>
 */
public record FiboParams(
        long swingTfMillis,        // 找推动腿的周期（默认 15m）
        int atrPeriod,
        double reversalAtrMult,    // ZigZag 反转确认阈值(×ATR)
        double minLegAtrMult,      // 腿最小长度(×ATR)
        double entryFib,           // 挂单回撤档（默认 0.66 黄金口袋深端）
        double invalidationRatio,  // 收盘破此档作废（默认 0.88，跟随 slFibRatio）
        double slFibRatio,         // 止损参考档（默认 0.88）
        double slBufferAtrMult,    // 止损在档位外再留的 ATR 缓冲
        double tpExtensionRatio,   // 止盈延伸档（默认 1.0=前高/前低，scaleOut 后剩余仓兜底）
        int orderTimeoutBars,      // 挂单超时（5m 根数，默认 12=1 小时）
        int swingLookbackBars,     // 找腿回看根数 + 腿龄上限
        boolean scaleOutOn,        // 止盈开关（默认开）
        double scaleOutFraction,   // 落袋比例（占开仓量，默认 1.0=全平）
        double scaleOutAtR,        // 落袋触发：浮盈达此 R 倍（默认 0.4=短线快速落袋）
        double tpRMultiple         // >0=止盈改用 R 倍(entry±R×risk，近 TP)；0=用斐波延伸位 tpExtensionRatio
) {

    public static FiboParams defaults() {
        return new FiboParams(
                900_000L,
                14, 2.0, 4.0,
                0.66, 0.88, 0.88,
                0.1, 1.0,
                12, 144,
                true, 1.0, 0.4,
                0.0);
    }

    /** 消融：开/关分批止盈，其余不动。 */
    public FiboParams withScaleOut(boolean on) {
        return new FiboParams(swingTfMillis, atrPeriod, reversalAtrMult, minLegAtrMult,
                entryFib, invalidationRatio, slFibRatio, slBufferAtrMult, tpExtensionRatio,
                orderTimeoutBars, swingLookbackBars,
                on, scaleOutFraction, scaleOutAtR, tpRMultiple);
    }

    /** 消融：止盈从斐波延伸位切到 R 倍(entry±r×risk)；r=0 回退延伸位。 */
    public FiboParams withTpRMultiple(double r) {
        return new FiboParams(swingTfMillis, atrPeriod, reversalAtrMult, minLegAtrMult,
                entryFib, invalidationRatio, slFibRatio, slBufferAtrMult, tpExtensionRatio,
                orderTimeoutBars, swingLookbackBars,
                scaleOutOn, scaleOutFraction, scaleOutAtR, r);
    }
}
