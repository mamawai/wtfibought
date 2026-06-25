package com.mawai.wiibquant.agent.research.series;

import java.math.BigDecimal;

/** research 内存层的"时点序列"单点：epoch 毫秒 + 数值。是 store 解析输出、aligner 输入的统一表示。 */
public record MarketSeriesPoint(long ts, BigDecimal value) {
}
