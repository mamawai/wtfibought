package com.mawai.wiibservice.agent.config;

public record RuntimeToggleSnapshot(
        boolean debateJudgeEnabled,
        boolean factorWeightOverrideEnabled,
        boolean macroRiskEnabled
    ) {
}
