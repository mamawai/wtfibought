package com.mawai.wiibservice.agent.quant.domain.output;

public record NewsEventFilteredNewsResponse(
        String title,
        String sentiment,
        String impact,
        String reason
) {
}
