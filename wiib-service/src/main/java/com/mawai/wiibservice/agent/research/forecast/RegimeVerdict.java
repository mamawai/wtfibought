package com.mawai.wiibservice.agent.research.forecast;

/** regime 核心腿输出：市场状态 + 置信 [0,1]。 */
public record RegimeVerdict(MarketRegime regime, double confidence) {
}
