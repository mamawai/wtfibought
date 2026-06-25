package com.mawai.wiibquant.agent.quant;

import com.mawai.wiibquant.agent.quant.domain.ForecastResult;

public record QuantForecastRunResult(
        boolean graphReturned,
        boolean dataAvailable,
        CryptoAnalysisReport report,
        ForecastResult forecastResult,
        String debateSummary,
        String rawSnapshotJson,
        String rawReportJson
) {

    public static QuantForecastRunResult graphFailed() {
        return new QuantForecastRunResult(false, false, null, null, null, null, null);
    }

    public static QuantForecastRunResult dataUnavailable() {
        return new QuantForecastRunResult(true, false, null, null, null, null, null);
    }

    public static QuantForecastRunResult completed(CryptoAnalysisReport report,
                                                   ForecastResult forecastResult,
                                                   String debateSummary,
                                                   String rawSnapshotJson,
                                                   String rawReportJson) {
        return new QuantForecastRunResult(true, true, report, forecastResult,
                debateSummary, rawSnapshotJson, rawReportJson);
    }

    public boolean reportValid() {
        return report != null && report.isValid();
    }
}
