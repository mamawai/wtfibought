package com.mawai.wiibquant.agent.quant.domain.output;

import java.util.List;

public record GenerateReportResponse(
        String reasoning,
        String summary,
        String analysisBasis,
        String indicators,
        List<String> riskWarnings
) {
}
