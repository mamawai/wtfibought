package com.mawai.wiibservice.agent.config;

public record RuntimeToggleSnapshot(
        boolean debateJudgeEnabled,
        boolean debateJudgeShadowEnabled,
        boolean factorWeightOverrideEnabled,
        TradingToggles trading,
        DrawdownSentinelToggles drawdown,
        CircuitBreakerToggles circuitBreaker
    ) {
    public record TradingToggles(
            boolean lowVolTradingEnabled
    ) {}

    public record DrawdownSentinelToggles(
            boolean enabled,
            int windowMinutes,
            double pnlPctDropThresholdPpt,
            double profitDrawdownThresholdPct,
            double profitDrawdownMinBase,
            int cooldownMinutes
    ) {}

    public record CircuitBreakerToggles(
            boolean enabled,
            double l1DailyNetLossPct,
            int l2LossStreak,
            int l2CooldownHours,
            double l3DrawdownPct
    ) {}
}
