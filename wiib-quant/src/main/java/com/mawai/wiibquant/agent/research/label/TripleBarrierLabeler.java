package com.mawai.wiibquant.agent.research.label;

import com.mawai.wiibcommon.market.KlineBar;

import java.math.BigDecimal;
import java.util.List;

/** 三隔栏打标：上栏 entry·(1+k·σ)、下栏 entry·(1−k·σ)、竖栏=pathBars 走完。 */
public final class TripleBarrierLabeler {

    private TripleBarrierLabeler() {
    }

    /**
     * 顺序扫 pathBars（决策点之后、竖栏之前的路径）：
     * 某根 high≥upper 即上栏先触，low≤lower 即下栏先触；同根都触用 close 相对 entry 裁决；
     * 走完都没触 → VERTICAL。路径越细（如 1m）同根双触越罕见。
     */
    public static BarrierLabel label(BigDecimal entryPrice, double k, double sigma, List<KlineBar> pathBars) {
        if (pathBars == null || pathBars.isEmpty()) return BarrierLabel.VERTICAL;
        BigDecimal upper = entryPrice.multiply(BigDecimal.valueOf(1.0 + k * sigma));
        BigDecimal lower = entryPrice.multiply(BigDecimal.valueOf(1.0 - k * sigma));
        for (KlineBar b : pathBars) {
            boolean upHit = b.high().compareTo(upper) >= 0;
            boolean lowHit = b.low().compareTo(lower) <= 0;
            if (upHit && lowHit) {
                return b.close().compareTo(entryPrice) >= 0 ? BarrierLabel.UPPER : BarrierLabel.LOWER;
            }
            if (upHit) return BarrierLabel.UPPER;
            if (lowHit) return BarrierLabel.LOWER;
        }
        return BarrierLabel.VERTICAL;
    }
}
