package com.mawai.wiibquant.market.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FeatureSnapshot(
        String symbol,
        LocalDateTime snapshotTime,
        BigDecimal lastPrice,
        BigDecimal barHigh,
        BigDecimal barLow,
        BigDecimal spotLastPrice,

        // 多周期技术指标: timeframe → indicatorName → value
        Map<String, Map<String, Object>> indicatorsByTimeframe,
        // 多周期价格变化: label → pct
        Map<String, BigDecimal> priceChanges,

        // 现货-合约联动
        double spotBidAskImbalance,
        BigDecimal spotPriceChange5m,
        double spotPerpBasisBps,
        // 现货vs合约同窗口收益差（相对强弱代理），正=现货更强，负=合约更强，非严格时间领先/滞后
        double spotLeadLagScore,

        // 盘口微结构
        double bidAskImbalance,
        double tradeDelta,
        // aggTrade 实时 order flow（WS 可用时为真实值，否则 0）
        double tradeIntensity,
        double largeTradeBias,
        double oiChangeRate,
        double fundingDeviation,
        double fundingRateTrend,
        double fundingRateExtreme,
        double lsrExtreme,

        // 爆仓压力: 正=多头爆仓多(空头力量), 负=空头爆仓多(多头力量), 归一化[-1,1]
        double liquidationPressure,
        // 近期爆仓总额(USDT)
        double liquidationVolumeUsdt,
        // 大户持仓趋势: 正=大户加多, 负=大户加空, 归一化[-1,1]
        double topTraderBias,
        // 主动买卖量比趋势: 正=主动买入主导, 负=主动卖出主导, 归一化[-1,1]
        double takerBuySellPressure,
        // 恐惧贪婪指数: 0=极度恐惧, 100=极度贪婪
        int fearGreedIndex,
        // 恐惧贪婪分类: Extreme Fear/Fear/Neutral/Greed/Extreme Greed
        String fearGreedLabel,

        // 波动率
        BigDecimal atr1m,
        BigDecimal atr,
        BigDecimal bollBandwidth,
        boolean bollSqueeze,

        // 期权隐含波动率（Deribit，0=无数据）
        double dvolIndex,
        double atmIv,
        double ivSkew25d,
        double ivTermSlope,

        // 市场状态
        MarketRegime regime,

        // 数据质量
        List<String> qualityFlags
) {
    public FeatureSnapshot {
        indicatorsByTimeframe = indicatorsByTimeframe == null ? Map.of() : Map.copyOf(indicatorsByTimeframe);
        priceChanges = priceChanges == null ? Map.of() : Map.copyOf(priceChanges);
        qualityFlags = qualityFlags == null ? List.of() : List.copyOf(qualityFlags);
        if (regime == null) {
            regime = MarketRegime.RANGE;
        }
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

