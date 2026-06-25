package com.mawai.wiibquant.agent.quant.domain.output;

import java.util.List;

public record DebateJudgeResponse(
        String judgeReasoning,
        List<DebateHorizonResponse> horizons
) {
}
