package com.mawai.wiibservice.agent.quant.domain.output;

import java.util.List;

public record DebateJudgeResponse(
        String judgeReasoning,
        List<DebateHorizonResponse> horizons
) {
}
