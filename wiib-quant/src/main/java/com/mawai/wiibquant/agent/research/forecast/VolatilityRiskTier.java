package com.mawai.wiibquant.agent.research.forecast;

/** vol 风险层级：只表达风险上下文，不直接代表交易方向。 */
public enum VolatilityRiskTier {
    UNKNOWN,
    QUIET,
    NORMAL,
    ELEVATED,
    STRESSED
}
