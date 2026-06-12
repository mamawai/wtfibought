package com.mawai.wiibservice.agent.strategy.core;

import java.math.BigDecimal;

/** 策略自带风控声明；不声明用 defaults()。后续接真实下单链时由适配层消费。 */
public record StrategyRiskPolicy(BigDecimal minRiskReward, int maxLeverage, double riskPerTradePct) {

    public static StrategyRiskPolicy defaults() {
        return new StrategyRiskPolicy(new BigDecimal("1.2"), 5, 0.01);
    }
}
