package com.mawai.wiibquant.agent.analysis;

/**
 * Judge 裁决的结构化输出：研判叙事 + 情景分布 + 失效条件 + 无方向态。
 * 没有任何"方向数字修正"字段——debate 是研判内容生产者，不是方向预测修正器。
 */
public record DeepAnalysisResponse(
        String narrative,
        Integer bullPct,
        Integer rangePct,
        Integer bearPct,
        Boolean noDirection,
        String invalidation,
        String judgeReasoning
) {
}
