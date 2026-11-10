package com.mawai.wiibquant.market.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FeatureSnapshot(
        String symbol,
        LocalDateTime snapshotTime,
        BigDecimal lastPrice,

        // 多周期技术指标: timeframe → indicatorName → value
        Map<String, Map<String, Object>> indicatorsByTimeframe,
        // 多周期价格变化: label → pct
        Map<String, BigDecimal> priceChanges,

        // 合约相对现货的溢价（基点），没有现货价时为 0
        double spotPerpBasisBps,

        // 恐惧贪婪指数: 0=极度恐惧, 100=极度贪婪
        int fearGreedIndex,
        // 恐惧贪婪分类: Extreme Fear/Fear/Neutral/Greed/Extreme Greed
        String fearGreedLabel,

        // 决策周期的 ATR14
        BigDecimal atr,

        // 期权隐含波动率（Deribit，0=无数据）
        double dvolIndex,
        double atmIv,
        double ivSkew25d,
        double ivTermSlope,

        // 数据质量
        List<String> qualityFlags
) {
    public FeatureSnapshot {
        indicatorsByTimeframe = indicatorsByTimeframe == null ? Map.of() : Map.copyOf(indicatorsByTimeframe);
        priceChanges = priceChanges == null ? Map.of() : Map.copyOf(priceChanges);
        qualityFlags = qualityFlags == null ? List.of() : List.copyOf(qualityFlags);
        if (fearGreedLabel == null) {
            fearGreedLabel = "UNKNOWN";
        }
    }

    /** @param noData 无数据时的占位文案——调用方按自己的语言/口径给（工具 JSON 给英文，深研判走词表） */
    public String toIvSummary(String noData) {
        // 缺哪段就不出哪段：SOL/XRP 没有 DVOL，写成 DVOL=0.0 会被当成读数
        String dvol = dvolIndex > 0 ? "DVOL=%.1f".formatted(dvolIndex) : "";
        String atm = atmIv > 0
                ? "ATM_IV=%.1f 25d_skew=%.2f term_slope=%.2f".formatted(atmIv, ivSkew25d, ivTermSlope) : "";
        String summary = (dvol + " " + atm).trim();
        return summary.isEmpty() ? noData : summary;
    }
}
