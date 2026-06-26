package com.mawai.wiibquant.agent.quant.domain.signal;

/**
 * 一条可读信号：把 agent 产出的 flag/reasonCode 提升为带人话标签 + 方向倾向的一等公民。
 *
 * @param code        原始 flag（如 HIGH_FUNDING），保留以便审计/溯源
 * @param label       人话标签（如 "资金费率偏高·多头拥挤(反向偏空)"）
 * @param lean        方向倾向
 * @param group       语义分组
 * @param sourceAgent 来源 agent（microstructure/momentum/regime/volatility/news_event）
 * @param evidence    可选数值/文字证据（如新闻影响级+理由）；无则 null
 */
public record Signal(
        String code,
        String label,
        SignalLean lean,
        SignalGroup group,
        String sourceAgent,
        String evidence
) {
    public Signal(String code, String label, SignalLean lean, SignalGroup group, String sourceAgent) {
        this(code, label, lean, group, sourceAgent, null);
    }
}
