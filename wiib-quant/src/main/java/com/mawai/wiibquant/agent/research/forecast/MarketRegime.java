package com.mawai.wiibquant.agent.research.forecast;

/**
 * 市场状态。研究底座自包含——不复用 live 的 {@code agent.quant.domain.MarketRegime}（5 类，且耦合 IV），
 * 避免研究包反向依赖 live。本刀按计划取 4 类，regime 分类法是开放参数（不够再扩）。
 */
public enum MarketRegime {
    /** 趋势上行。 */
    TRENDING_UP,
    /** 趋势下行。 */
    TRENDING_DOWN,
    /** 震荡（无明确方向、低单边动能）。 */
    RANGING,
    /** 冲击（剧烈无方向波动，风险态）。 */
    SHOCK
}
