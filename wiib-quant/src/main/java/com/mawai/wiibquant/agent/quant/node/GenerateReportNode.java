package com.mawai.wiibquant.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibquant.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibquant.agent.quant.domain.*;
import com.mawai.wiibquant.agent.quant.domain.debate.WeakLean;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityScore;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;
import com.mawai.wiibquant.agent.quant.factor.NewsEventAgent;
import com.mawai.wiibquant.agent.quant.memory.MemoryService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告生成节点（LLM）。
 * 输入：结构化的ForecastResult数据（区间裁决、Agent投票、风控结果）。
 * 输出：CryptoAnalysisReport（前端兼容格式）。
 */
@Slf4j
public class GenerateReportNode implements NodeAction {

    private final ReportHardReportBuilder hardReportBuilder;
    private final ReportPromptBuilder promptBuilder = new ReportPromptBuilder();
    private final ReportResponseMerger responseMerger = new ReportResponseMerger();
    private final ReportSnapshotSerializer snapshotSerializer = new ReportSnapshotSerializer();
    private final ChatClient chatClient;
    private final LlmCallMode callMode;
    private final MemoryService memoryService;

    public GenerateReportNode(ChatClient.Builder builder, LlmCallMode callMode, MemoryService memoryService) {
        this.chatClient = builder.build();
        this.callMode = callMode;
        this.memoryService = memoryService;
        this.hardReportBuilder = new ReportHardReportBuilder();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        String symbol = (String) state.value("target_symbol").orElse("BTCUSDT");
        log.info("[Q6.0] generate_report开始 symbol={}", symbol);
        List<HorizonForecast> forecasts =
                (List<HorizonForecast>) state.value("horizon_forecasts").orElse(List.of());
        List<AgentVote> votes =
                (List<AgentVote>) state.value("agent_votes").orElse(List.of());
        List<NewsEventAgent.FilteredNewsItem> filteredNews =
                (List<NewsEventAgent.FilteredNewsItem>) state.value("filtered_news").orElse(List.of());
        String overallDecision = (String) state.value("overall_decision").orElse("FLAT");
        String riskStatus = (String) state.value("risk_status").orElse("UNKNOWN");
        FeatureSnapshot snapshot =
                (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        MacroContext macroContext =
                (MacroContext) state.value("macro_context").orElse(null);
        String indicators = StateHelper.stateJson(state, "indicator_map");
        String priceChanges = StateHelper.stateJson(state, "price_change_map");

        Map<String, Object[]> debateProbs =
                (Map<String, Object[]>) state.value("debate_probs").orElse(Map.of());

        // ===== Step1: 硬性报告（纯计算，零LLM） =====
        CryptoAnalysisReport hardReport = hardReportBuilder.build(
                symbol, forecasts, votes, filteredNews, overallDecision, riskStatus,
                snapshot, macroContext, debateProbs);
        log.info("[Q6.1] 硬性报告构建完成 dirs=[{}/{}/{}] confidence={} positions={} news={} warnings={}",
                hardReport.getDirection().getUltraShort(), hardReport.getDirection().getShortTerm(),
                hardReport.getDirection().getMid(), hardReport.getConfidence(),
                hardReport.getPositionAdvice().size(), hardReport.getImportantNews().size(),
                hardReport.getRiskWarnings().size());

        // ===== Step2: LLM综合推理 =====
        CryptoAnalysisReport finalReport;
        String debateSummaryRaw = (String) state.value("debate_summary").orElse(null);
        try {
            String memorySummary = buildMemorySummary(snapshot);
            String prompt = promptBuilder.build(hardReport, votes, indicators, priceChanges,
                    filteredNews, riskStatus, snapshot, debateSummaryRaw, memorySummary);
            String response = callMode.call(chatClient, prompt);
            log.info("[Q6.2] LLM推理返回 {}chars 耗时{}ms",
                    response != null ? response.length() : 0, System.currentTimeMillis() - startMs);

            // ===== Step3: 合并→最终报告 =====
            finalReport = responseMerger.merge(hardReport, response);
        } catch (Exception e) {
            log.warn("[Q6.2] LLM推理失败，最终报告=硬性报告: {}", e.getMessage());
            finalReport = hardReport;
        }

        // 辩论摘要写入报告
        if (debateSummaryRaw != null && !debateSummaryRaw.isBlank()) {
            try {
                JSONObject ds = JSON.parseObject(debateSummaryRaw);
                CryptoAnalysisReport.DebateSummary debate = new CryptoAnalysisReport.DebateSummary();
                debate.setBullArgument(ds.getString("bullArgument"));
                debate.setBearArgument(ds.getString("bearArgument"));
                debate.setJudgeReasoning(ds.getString("judgeReasoning"));
                finalReport.setDebateSummary(debate);
            } catch (Exception e) {
                log.warn("[Q6] 辩论摘要解析失败: {}", e.getMessage());
            }
        }

        if (!finalReport.isValid()) {
            log.error("[Q6] 报告关键字段缺失");
            return Map.of("data_available", false);
        }

        log.info("[Q6.3] 最终报告 summary={} reasoning={}chars",
                finalReport.getSummary(),
                finalReport.getReasoning() != null ? finalReport.getReasoning().length() : 0);

        // 4a：把展示产物（脆弱度/信号面板/弱lean）带进 ForecastResult → 落 briefing_json
        FragilityScore fragility = (FragilityScore) state.value("fragility_score").orElse(null);
        SignalPanel signalPanel = (SignalPanel) state.value("signal_panel").orElse(SignalPanel.empty());
        List<WeakLean> weakLeans = (List<WeakLean>) state.value("weak_leans").orElse(List.of());

        ForecastResult forecastResult = new ForecastResult(symbol,
                (String) state.value("cycle_id").orElse("unknown"),
                snapshot != null ? snapshot.snapshotTime() : java.time.LocalDateTime.now(),
                forecasts, overallDecision, riskStatus, votes, snapshot, macroContext, finalReport,
                fragility, signalPanel, weakLeans, null);

        log.info("[Q6.end] generate_report完成 总耗时{}ms", System.currentTimeMillis() - startMs);

        Map<String, Object> result = new HashMap<>();
        result.put("report", finalReport);
        result.put("hard_report", hardReport);
        // 序列化为JSON字符串，避免框架deepCopy把record转成Map
        result.put("forecast_result", JSON.toJSONString(forecastResult));
        // 单独输出序列化后的JSON，避免ForecastResult反序列化时复杂record丢失
        if (snapshot != null) {
            result.put("raw_snapshot_json", snapshotSerializer.serialize(snapshot, state));
        }
        result.put("raw_report_json", JSON.toJSONString(finalReport));
        return result;
    }

    private String buildMemorySummary(FeatureSnapshot snapshot) {
        if (memoryService == null || snapshot == null) return "";
        try {
            return memoryService.buildAccuracySummary(snapshot.symbol());
        } catch (Exception e) {
            return "";
        }
    }
}
