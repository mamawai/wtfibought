package com.mawai.wiibservice.agent.trading;

import java.util.Map;

/**
 * 按币种差异化的交易参数。
 * PAXG（黄金稳定币）波动远低于BTC/ETH，需要更大的ATR乘数和更小的止损距离下限。
 */
public record SymbolProfile(
        double trendSlAtr,
        double trendTpAtr,
        double revertSlAtr,
        double revertTpMinAtr,
        double revertTpMaxAtr,
        double breakoutSlAtr,
        double breakoutTpAtr,
        double trailBreakevenAtr,
        double trailLockAtr,
        double slMinPct,
        double slMaxPct,
        double partialTpAtr,
        double trailGapAtr
) {
    // Sprint A2-1：SL只放松一档，MR TP下限同步抬高，保证后续R:R守卫有空间
    // trailBreakevenAtr 1.0→0.5 让浮盈0.5ATR即保本，先守住回撤噪音
    // trailLockAtr 3.0→1.5 浮盈1.5ATR即启动追踪锁利（而非3ATR才追）
    // partialTpAtr=1.5 分批止盈点，1.5ATR 平50%落袋
    // trailGapAtr=0.8 追踪止损 gap，独立于 breakeven，防 trail 太松吃回利润
    // Phase 0A 止血：trendTpAtr / breakoutTpAtr 3.0→4.5（×1.5），剩余 50% 仓位追更远目标修正盈亏比（§2.3 A）
    private static final SymbolProfile BTC = new SymbolProfile(
            1.8, 4.5, 1.6, 2.0, 3.0, 2.0, 4.5, 0.5, 1.5, 0.005, 0.10, 1.5, 0.8);
    private static final SymbolProfile ETH = new SymbolProfile(
            1.8, 4.5, 1.6, 2.0, 3.0, 2.0, 4.5, 0.5, 1.5, 0.005, 0.10, 1.5, 0.8);
    // PAXG 低波动：门槛按比例放宽
    private static final SymbolProfile PAXG = new SymbolProfile(
            3.5, 6.0, 3.0, 2.5, 5.0, 4.0, 6.0, 1.2, 2.0, 0.002, 0.05, 2.0, 1.2);

    private static final Map<String, SymbolProfile> PROFILES = Map.of(
            "BTCUSDT", BTC, "ETHUSDT", ETH, "PAXGUSDT", PAXG);

    public static SymbolProfile of(String symbol) {
        return PROFILES.getOrDefault(symbol, BTC);
    }
}
