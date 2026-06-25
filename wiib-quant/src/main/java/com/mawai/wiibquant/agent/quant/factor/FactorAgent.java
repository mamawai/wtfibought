package com.mawai.wiibquant.agent.quant.factor;

import com.mawai.wiibquant.agent.quant.domain.AgentVote;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;

import java.util.List;

/**
 * 量化因子 Agent 的统一接口。
 * 输入一份 FeatureSnapshot，输出三个 horizon 的方向、置信度、预期波动和原因码。
 */
public interface FactorAgent {

    String name();

    List<AgentVote> evaluate(FeatureSnapshot snapshot);

    default List<AgentVote> evaluate(FeatureSnapshot snapshot, FactorEvaluationContext context) {
        return evaluate(snapshot);
    }
}
