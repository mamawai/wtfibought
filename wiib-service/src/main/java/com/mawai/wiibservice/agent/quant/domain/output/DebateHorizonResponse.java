package com.mawai.wiibservice.agent.quant.domain.output;

public record DebateHorizonResponse(
        String horizon,
        Boolean approved,
        String newDirection,
        Double newConfidence,
        String reason,
        Integer bullPct,
        Integer rangePct,
        Integer bearPct
) {
}
