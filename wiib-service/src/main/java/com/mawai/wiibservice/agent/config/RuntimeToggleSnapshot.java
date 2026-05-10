package com.mawai.wiibservice.agent.config;

public record RuntimeToggleSnapshot(
        boolean debateJudgeEnabled,
        boolean debateJudgeShadowEnabled,
        boolean factorWeightOverrideEnabled,
        TradingToggles trading,
        CircuitBreakerToggles circuitBreaker
    ) {
    public record TradingToggles(
            boolean lowVolTradingEnabled,
            boolean playbookExitEnabled
    ) {}

    public record CircuitBreakerToggles(
            boolean enabled,
            double l1DailyNetLossPct,
            int l2LossStreak,
            int l2CooldownHours,
            double l3DrawdownPct
    ) {}
}
