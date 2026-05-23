package com.mawai.wiibservice.agent.config;

import java.util.List;

public record RuntimeToggleSnapshot(
        boolean debateJudgeEnabled,
        boolean debateJudgeShadowEnabled,
        boolean factorWeightOverrideEnabled,
        TradingToggles trading,
        CircuitBreakerToggles circuitBreaker
    ) {
    public record TradingToggles(
            boolean lowVolTradingEnabled,
            boolean playbookExitEnabled,
            List<String> entryEnabledStrategies
    ) {}

    public record CircuitBreakerToggles(
            boolean enabled,
            double l1DailyNetLossPct,
            int l2LossStreak,
            int l2CooldownHours,
            double l3DrawdownPct
    ) {}
}
