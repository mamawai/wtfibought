package com.mawai.wiibservice.agent.quant;

import com.mawai.wiibservice.agent.quant.domain.ForecastResult;

public record QuantForecastRunResult(
        boolean graphReturned,
        boolean dataAvailable,
        CryptoAnalysisReport report,
        ForecastResult forecastResult,
        String debateSummary,
        String rawSnapshotJson,
        String rawReportJson
) {

    public boolean reportValid() {
        return report != null && report.isValid();
    }
}
