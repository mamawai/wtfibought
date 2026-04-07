package com.mawai.wiibservice.agent.quant.domain;

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

        // 盘口微结构
        double bidAskImbalance,
        double tradeDelta,
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

        // 市场状态
        MarketRegime regime,

        // 新闻
        List<NewsItem> newsItems,

        // 数据质量
        List<String> qualityFlags,

        // regime审核结果（RegimeReviewNode产出）
        double regimeConfidence,
        String regimeTransition
) {}

