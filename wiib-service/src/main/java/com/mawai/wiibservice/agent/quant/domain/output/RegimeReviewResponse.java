package com.mawai.wiibservice.agent.quant.domain.output;

public record RegimeReviewResponse(
        String confirmedRegime,
        double confidence,
        String reasoning,
        String transitionSignal,
        String transitionDetail
) {
}
