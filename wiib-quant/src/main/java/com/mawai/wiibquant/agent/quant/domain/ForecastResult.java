package com.mawai.wiibquant.agent.quant.domain;

import java.time.LocalDateTime;
import java.util.List;

public record ForecastResult(
        String symbol,
        String cycleId,
        LocalDateTime forecastTime,
        List<HorizonForecast> horizons,
        String overallDecision,
        String riskStatus,
        List<AgentVote> allVotes,
        FeatureSnapshot snapshot,    // 快照，用于事后验证
        MacroContext macroContext,
        Object report,
        String parentCycleId         // 旧轻周期兼容字段；新 research 主链路始终为 null
) {}
