package com.mawai.wiibquant.agent.quant.domain.briefing;

import com.mawai.wiibquant.agent.quant.domain.debate.WeakLean;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityScore;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;

import java.util.List;

/**
 * 简报快照：一轮 cycle 的展示产物合集，落 {@code quant_forecast_cycle.briefing_json}（jsonb）。
 *
 * <p>Step 7 展示 / Step 4b revision diff 的单一读取源。三者均为确定性/已落库产物，
 * 一起读写避免散列。</p>
 */
public record BriefingSnapshot(
        FragilityScore fragility,
        SignalPanel signalPanel,
        List<WeakLean> weakLeans
) {
    public static BriefingSnapshot of(FragilityScore fragility, SignalPanel signalPanel,
                                      List<WeakLean> weakLeans) {
        return new BriefingSnapshot(
                fragility,
                signalPanel == null ? SignalPanel.empty() : signalPanel,
                weakLeans == null ? List.of() : weakLeans);
    }
}
