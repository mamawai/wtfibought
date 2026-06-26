package com.mawai.wiibquant.agent.quant.domain;

import com.mawai.wiibquant.agent.quant.domain.debate.WeakLean;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityScore;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;

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
        FragilityScore fragility,    // 脆弱度（Step 4a 落 briefing_json）
        SignalPanel signalPanel,     // 信号面板（同上）
        List<WeakLean> weakLeans,    // 弱 lean（同上）
        String parentCycleId         // 旧轻周期兼容字段；新 research 主链路始终为 null
) {}
