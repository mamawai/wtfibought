package com.mawai.wiibservice.agent.quant.domain;

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
        Object report,
        String parentCycleId         // 轻周期挂载的父重周期 cycleId；重周期为 null
) {}
