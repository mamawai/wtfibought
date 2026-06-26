package com.mawai.wiibquant.agent.config;

public record RuntimeToggleSnapshot(
        boolean debateJudgeEnabled,
        boolean macroRiskEnabled
    ) {
}
