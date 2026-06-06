package com.mawai.wiibservice.agent.quant.node;

import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibservice.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibservice.agent.quant.domain.output.GenerateReportResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.ArrayList;
import java.util.List;

@Slf4j
final class ReportResponseMerger {

    private static final BeanOutputConverter<GenerateReportResponse> REPORT_CONVERTER =
            new BeanOutputConverter<>(GenerateReportResponse.class);

    CryptoAnalysisReport merge(CryptoAnalysisReport hardReport, String llmResponse) {
        CryptoAnalysisReport merged = new CryptoAnalysisReport();
        // 结构化字段：来自硬性报告（不可修改）
        merged.setDirection(hardReport.getDirection());
        merged.setKeyLevels(hardReport.getKeyLevels());
        merged.setPositionAdvice(hardReport.getPositionAdvice());
        merged.setImportantNews(hardReport.getImportantNews());
        merged.setConfidence(hardReport.getConfidence());
        merged.setMacroContext(hardReport.getMacroContext());

        if (llmResponse == null || llmResponse.isBlank()) {
            copyTextFields(merged, hardReport);
            return merged;
        }

        try {
            String json = JsonUtils.extractJson(llmResponse);
            GenerateReportResponse output = REPORT_CONVERTER.convert(json);

            merged.setReasoning(blankToDefault(output.reasoning(), null));
            merged.setSummary(blankToDefault(output.summary(), hardReport.getSummary()));
            merged.setAnalysisBasis(blankToDefault(output.analysisBasis(), hardReport.getAnalysisBasis()));
            merged.setIndicators(blankToDefault(output.indicators(), hardReport.getIndicators()));
            merged.setRiskWarnings(nonBlankWarnings(output.riskWarnings(), hardReport.getRiskWarnings()));
        } catch (Exception e) {
            log.warn("[Q6.merge] LLM输出解析失败，文本字段回退硬性报告: {}", e.getMessage());
            copyTextFields(merged, hardReport);
        }
        return merged;
    }

    private void copyTextFields(CryptoAnalysisReport target, CryptoAnalysisReport source) {
        target.setSummary(source.getSummary());
        target.setAnalysisBasis(source.getAnalysisBasis());
        target.setIndicators(source.getIndicators());
        target.setRiskWarnings(source.getRiskWarnings());
        target.setReasoning(source.getReasoning());
    }

    private String blankToDefault(String value, String defaultValue) {
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    private List<String> nonBlankWarnings(List<String> warnings, List<String> fallback) {
        if (warnings == null || warnings.isEmpty()) {
            return fallback;
        }
        List<String> cleaned = new ArrayList<>();
        for (String warning : warnings) {
            if (warning != null && !warning.isBlank()) {
                cleaned.add(warning);
            }
        }
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
