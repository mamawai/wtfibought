package com.mawai.wiibquant.agent.quant.domain.output;

import java.util.List;

public record NewsEventVoteResponse(
        String horizon,
        double score,
        double confidence,
        List<String> reasonCodes,
        List<String> riskFlags
) {
}
