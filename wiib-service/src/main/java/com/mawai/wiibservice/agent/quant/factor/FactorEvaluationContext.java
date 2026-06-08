package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.MacroContext;
import com.mawai.wiibservice.agent.research.ForecastHorizon;

/**
 * Evidence agent 的本轮信息上下文。
 * 承载 research 三腿预测，供需要对照主方向的 evidence agent 使用。
 */
public record FactorEvaluationContext(MacroContext researchForecast) {

    public static FactorEvaluationContext empty() {
        return new FactorEvaluationContext(null);
    }

    public MacroContext.Leg researchLeg(ForecastHorizon horizon) {
        if (researchForecast == null || horizon == null) {
            return MacroContext.Leg.neutral();
        }
        return researchForecast.legs().getOrDefault(horizon, MacroContext.Leg.neutral());
    }
}
