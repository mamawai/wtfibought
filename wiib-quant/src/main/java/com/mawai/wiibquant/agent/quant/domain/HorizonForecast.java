package com.mawai.wiibquant.agent.quant.domain;

import java.math.BigDecimal;

public record HorizonForecast(
        String horizon,           // H6 / H12 / H24
        Direction direction,      // LONG / SHORT / NO_TRADE
        double confidence,        // 综合置信度 [0, 1]
        double weightedScore,     // 加权得分，正=多头优势
        double disagreement,      // 分歧度 [0, 1]，越高信号越分裂
        BigDecimal entryLow,      // 新预测层不再生成，通常为 null
        BigDecimal entryHigh,     // 新预测层不再生成，通常为 null
        BigDecimal invalidationPrice, // 新预测层不再生成，通常为 null
        BigDecimal tp1,           // 新预测层不再生成，通常为 null
        BigDecimal tp2,           // 新预测层不再生成，通常为 null
        int maxLeverage,          // RiskGate 生成的最大杠杆倍数
        double maxPositionPct     // RiskGate 生成的最大仓位比例，如0.08=8%
) {
    public static HorizonForecast noTrade(String horizon, double disagreement) {
        return new HorizonForecast(horizon, Direction.NO_TRADE, 0, 0, disagreement,
                null, null, null, null, null, 0, 0);
    }
}
