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
        double slMaxPct
) {
    // trailBreakevenAtr 1.0→2.0 防5m级浮盈<0.2%就被保本止损打掉
    // trailLockAtr 2.0→3.0 让利润有追踪空间
    private static final SymbolProfile BTC = new SymbolProfile(
            1.5, 3.0, 1.5, 1.5, 3.0, 2.0, 3.0, 2.0, 3.0, 0.005, 0.10);
    private static final SymbolProfile ETH = new SymbolProfile(
            1.5, 3.0, 1.5, 1.5, 3.0, 2.0, 3.0, 2.0, 3.0, 0.005, 0.10);
    private static final SymbolProfile PAXG = new SymbolProfile(
            3.5, 6.0, 3.0, 2.5, 5.0, 4.0, 6.0, 2.0, 3.5, 0.002, 0.05);

    private static final Map<String, SymbolProfile> PROFILES = Map.of(
            "BTCUSDT", BTC, "ETHUSDT", ETH, "PAXGUSDT", PAXG);

    public static SymbolProfile of(String symbol) {
        return PROFILES.getOrDefault(symbol, BTC);
    }
}
