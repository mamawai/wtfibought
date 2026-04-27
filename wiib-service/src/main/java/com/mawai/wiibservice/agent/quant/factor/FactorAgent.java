package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;

import java.util.List;

/**
 * 量化因子 Agent 的统一接口。
 * 输入一份 FeatureSnapshot，输出三个 horizon 的方向、置信度、预期波动和原因码。
 */
public interface FactorAgent {

    String name();

    List<AgentVote> evaluate(FeatureSnapshot snapshot);
}
