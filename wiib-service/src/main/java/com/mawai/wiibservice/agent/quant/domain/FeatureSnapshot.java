package com.mawai.wiibservice.agent.quant.domain;

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
        BigDecimal atr5m,
        BigDecimal bollBandwidth,
        boolean bollSqueeze,

        // 期权隐含波动率（Deribit，0=无数据）
        double dvolIndex,
        double atmIv,
        double ivSkew25d,
        double ivTermSlope,

        // 市场状态
        MarketRegime regime,

        // 新闻
        List<NewsItem> newsItems,

        // 数据质量
        List<String> qualityFlags,

        // regime审核结果（RegimeReviewNode产出）
        double regimeConfidence,
        String regimeTransition
) {
    public FeatureSnapshot withRegimeReview(MarketRegime newRegime, List<String> newFlags,
                                            double confidence, String transition) {
        return new FeatureSnapshot(symbol, snapshotTime, lastPrice, barHigh, barLow, spotLastPrice,
                indicatorsByTimeframe, priceChanges,
                spotBidAskImbalance, spotPriceChange5m, spotPerpBasisBps, spotLeadLagScore,
                bidAskImbalance, tradeDelta, tradeIntensity, largeTradeBias, oiChangeRate,
                fundingDeviation, fundingRateTrend, fundingRateExtreme, lsrExtreme,
                liquidationPressure, liquidationVolumeUsdt,
                topTraderBias, takerBuySellPressure, fearGreedIndex, fearGreedLabel,
                atr1m, atr5m, bollBandwidth, bollSqueeze,
                dvolIndex, atmIv, ivSkew25d, ivTermSlope,
                newRegime, newsItems, newFlags, confidence, transition);
    }

    public String toIvSummary() {
        if (dvolIndex <= 0 && atmIv <= 0) return "无数据";
        return "DVOL=%.1f ATM_IV=%.1f 25d_skew=%.2f term_slope=%.2f"
                .formatted(dvolIndex, atmIv, ivSkew25d, ivTermSlope);
    }
}

