package com.mawai.wiibquant.agent.strategy.fibo;

/**
 * fibo 策略参数组（limit 回踩范式）；回测扫参时整组替换，避免散落魔法常量。
 *
 * <p>入场在 entryFib(默认 0.66 黄金口袋深端)挂限价单等回踩；止损固定 slFibRatio(默认 1.0=腿完全回撤
 * 即叙事失效)位+ATR缓冲，止盈默认持到斐波延伸位(1.0=前高/前低，让利润跑，消融最优)。
 * swingLookbackBars 既是找腿回看根数，也是 armed 腿龄上限（腿终点滑出窗口即作废）。
 * orderTimeoutBars 是挂单超时(5m 根数)：挂单后这么多根未成交即撤。</p>
 */
public record FiboParams(
        long swingTfMillis,        // 找推动腿的周期（默认 15m）
        int atrPeriod,
        double reversalAtrMult,    // ZigZag 反转确认阈值(×ATR)
        double minLegAtrMult,      // 腿最小长度(×ATR)
        double entryFib,           // 挂单回撤档（默认 0.66 黄金口袋深端）
        double invalidationRatio,  // 收盘破此档作废（默认 1.0，跟随 slFibRatio）
        double slFibRatio,         // 止损参考档（默认 1.0；网格消融 7/8 组费后 pf 优于 0.88——止损放宽55%→1%风险定量下名义缩小→费/R 降约1/3，且胜率+8~10pp）
        double slBufferAtrMult,    // 止损在档位外再留的 ATR 缓冲
        double tpExtensionRatio,   // 止盈延伸档（默认 1.0=前高/前低）
        int orderTimeoutBars,      // 挂单超时（5m 根数，默认 12=1 小时）
        int swingLookbackBars,     // 找腿回看根数 + 腿龄上限
        double tpRMultiple,        // >0=止盈改用 R 倍(entry±R×risk，近 TP)；0=用斐波延伸位 tpExtensionRatio
        boolean trendFilterOn,     // 高周期趋势过滤：仅放行与 1h(SMA200) 趋势同向的腿（四币消融验证为改善）
        boolean trendAlignOn       // T1 均线多头排列门（趋势闸开时附加 SMA50>SMA200 且价在快线上）；四币样本外验证为改善
) {

    public static FiboParams defaults() {
        return new FiboParams(
                900_000L,
                14, 2.0, 4.0,
                0.66, 1.0, 1.0,
                0.1, 1.0,
                12, 144,
                0.0,
                true,                 // trendFilterOn=true：只做与 1h SMA200 同向的腿(四币消融验证为改善)
                true);                // trendAlignOn=true：T1 均线多头排列门(四币样本外+毛收益一致改善，已采纳)
    }

    /** 消融：止盈从斐波延伸位切到 R 倍(entry±r×risk)；r=0 回退延伸位。 */
    public FiboParams withTpRMultiple(double r) {
        return new FiboParams(swingTfMillis, atrPeriod, reversalAtrMult, minLegAtrMult,
                entryFib, invalidationRatio, slFibRatio, slBufferAtrMult, tpExtensionRatio,
                orderTimeoutBars, swingLookbackBars,
                r, trendFilterOn, trendAlignOn);
    }
}
