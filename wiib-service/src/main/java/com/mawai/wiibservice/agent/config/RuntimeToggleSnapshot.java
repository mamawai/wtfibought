package com.mawai.wiibservice.agent.config;

public record RuntimeToggleSnapshot(
        boolean debateJudgeEnabled,
        boolean debateJudgeShadowEnabled,
        boolean factorWeightOverrideEnabled,
        TradingToggles trading,
        DrawdownSentinelToggles drawdown
) {
    public record TradingToggles(
            boolean lowVolTradingEnabled,
            boolean legacyThreshold5of7Enabled,
            boolean legacy5of7ShadowEnabled
    ) {}

    public record DrawdownSentinelToggles(
            boolean enabled,
            int windowMinutes,
            double pnlPctDropThresholdPpt,
            double profitDrawdownThresholdPct,
            double profitDrawdownMinBase,
            int cooldownMinutes
    ) {}
}
