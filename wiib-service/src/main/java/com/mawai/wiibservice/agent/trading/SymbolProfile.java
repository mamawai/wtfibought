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
        double trailGapAtr,
        double breakoutHardTpR,
        double trendHardTpR
) {
    private static final double DEFAULT_PLAYBOOK_HARD_TP_R = 5.0;

    public SymbolProfile(double trendSlAtr, double trendTpAtr,
                         double revertSlAtr, double revertTpMinAtr, double revertTpMaxAtr,
                         double breakoutSlAtr, double breakoutTpAtr,
                         double trailBreakevenAtr, double trailLockAtr,
                         double slMinPct, double slMaxPct,
                         double partialTpAtr, double trailGapAtr) {
        this(trendSlAtr, trendTpAtr, revertSlAtr, revertTpMinAtr, revertTpMaxAtr,
                breakoutSlAtr, breakoutTpAtr, trailBreakevenAtr, trailLockAtr,
                slMinPct, slMaxPct, partialTpAtr, trailGapAtr,
                DEFAULT_PLAYBOOK_HARD_TP_R, DEFAULT_PLAYBOOK_HARD_TP_R);
    }

    // BTC/ETH 虚拟盘观察档：SL 扛住 5m 正常波动，靠缩小仓位控制单笔亏损。
    // 盯盘不再早保本/早追踪，TP 需要给足空间，优先等完整止盈。
    private static final SymbolProfile BTC = new SymbolProfile(
            2.6, 5.2, 2.4, 3.2, 4.2, 2.8, 5.8, 0.5, 1.5, 0.008, 0.12, 1.5, 0.8,
            DEFAULT_PLAYBOOK_HARD_TP_R, DEFAULT_PLAYBOOK_HARD_TP_R);
    private static final SymbolProfile ETH = new SymbolProfile(
            2.6, 5.2, 2.4, 3.2, 4.2, 2.8, 5.8, 0.5, 1.5, 0.008, 0.12, 1.5, 0.8,
            DEFAULT_PLAYBOOK_HARD_TP_R, DEFAULT_PLAYBOOK_HARD_TP_R);
    // PAXG 低波动：门槛按比例放宽
    private static final SymbolProfile PAXG = new SymbolProfile(
            3.5, 6.0, 3.0, 2.5, 5.0, 4.0, 6.0, 1.2, 2.0, 0.002, 0.05, 2.0, 1.2,
            DEFAULT_PLAYBOOK_HARD_TP_R, DEFAULT_PLAYBOOK_HARD_TP_R);

    private static final Map<String, SymbolProfile> PROFILES = Map.of(
            "BTCUSDT", BTC, "ETHUSDT", ETH, "PAXGUSDT", PAXG);

    public static SymbolProfile of(String symbol) {
        return PROFILES.getOrDefault(symbol, BTC);
    }
}
