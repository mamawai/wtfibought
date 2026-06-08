package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibservice.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibservice.agent.quant.factor.NewsEventAgent;

import java.util.List;

final class ReportPromptBuilder {

    String build(CryptoAnalysisReport hardReport,
                 List<AgentVote> votes,
                 String indicators,
                 String priceChanges,
                 List<NewsEventAgent.FilteredNewsItem> filteredNews,
                 String riskStatus,
                 FeatureSnapshot snapshot,
                 String debateSummary,
                 String memorySummary) {
        String directionBlock = "短周期(H6/6h): %s\n中周期(H12/12h): %s\n长周期(H24/24h): %s\n超长线: 观望"
                .formatted(hardReport.getDirection().getUltraShort(),
                        hardReport.getDirection().getShortTerm(),
                        hardReport.getDirection().getMid());

        StringBuilder posBlock = new StringBuilder();
        for (CryptoAnalysisReport.PositionAdvice pa : hardReport.getPositionAdvice()) {
            posBlock.append(pa.getPeriod()).append(": 方向=").append(pa.getType());
            if (!"NO_TRADE".equals(pa.getType())) {
                posBlock.append(" 具体入场/止损/止盈由交易执行层生成");
            }
            posBlock.append("\n");
        }

        StringBuilder newsBlock = new StringBuilder();
        if (!filteredNews.isEmpty()) {
            for (NewsEventAgent.FilteredNewsItem item : filteredNews) {
                newsBlock.append("- [").append(item.sentiment()).append("/").append(item.impact()).append("] ")
                        .append(item.title()).append(" → ").append(item.reason()).append("\n");
            }
        } else {
            newsBlock.append("无重要新闻\n");
        }

        String regimeText = snapshot != null ? snapshot.regime().name() : "UNKNOWN";
        String qualityText = snapshot != null && snapshot.qualityFlags() != null && !snapshot.qualityFlags().isEmpty()
                ? String.join(", ", snapshot.qualityFlags()) : "正常";

        return """
                你是加密货币量化分析师。系统已完成全部结构化计算，请基于以下数据进行综合推理并撰写分析。

                【方向裁决（不可修改）】
                %s

                【方向与风控约束（不可修改；具体入场/止损/止盈由交易执行层生成）】
                %s
                【关键价位】
                支撑: %s  阻力: %s

                【系统置信度】%d%%
                【风控状态】%s
                【市场状态】%s
                【宏观上下文】%s
                【数据质量】%s

                【Agent投票详情】
                %s

                【技术指标快照】%s
                【多周期涨跌幅】%s

                【重要新闻】
                %s

                【辩论裁决结论】
                %s

                【历史表现参考】
                %s

                你的任务：
                1. reasoning: 综合推理——分析各维度信号（动量/微结构/波动率/市场状态/新闻）的一致性与矛盾，解释裁决的合理性或局限性
                2. summary: 一句话总结当前市场状态和建议（用📈📉↔️标注方向）
                3. analysisBasis: 核心预测依据（2-3句话，说明为什么得出这个结论）
                4. indicators: 用通俗语言解读关键指标状态，关键术语首次出现时加简短注释如RSI=35（偏超卖）
                5. riskWarnings: 风险提示列表

                约束：
                - 方向判断和风控约束已由量化系统确定，你只能解释不能修改
                - 如果裁决是观望/NO_TRADE，必须写观望
                - 不能新增系统未给出的概率、胜率或极端判断

                严格返回JSON（不要markdown包裹）：
                {
                  "reasoning": "综合推理分析",
                  "summary": "一句话总结",
                  "analysisBasis": "预测依据",
                  "indicators": "指标解读",
                  "riskWarnings": ["风险1", "风险2"]
                }
                """.formatted(
                directionBlock, posBlock.toString(),
                hardReport.getKeyLevels().getSupport(), hardReport.getKeyLevels().getResistance(),
                hardReport.getConfidence(), riskStatus, regimeText, hardReport.getMacroContext(), qualityText,
                buildVoteSummary(votes), indicators, priceChanges, newsBlock.toString(),
                formatDebateSummary(debateSummary),
                memorySummary != null ? memorySummary : "");
    }

    private String buildVoteSummary(List<AgentVote> votes) {
        StringBuilder sb = new StringBuilder();
        for (AgentVote v : votes) {
            sb.append(v.agent()).append("[").append(v.horizon()).append("]: ")
                    .append(v.direction()).append(" score=").append(String.format("%.2f", v.score()))
                    .append(" conf=").append(String.format("%.2f", v.confidence()));
            if (!v.reasonCodes().isEmpty()) {
                sb.append(" reasons=").append(v.reasonCodes().stream().distinct().toList());
            }
            if (!v.riskFlags().isEmpty()) {
                sb.append(" flags=").append(v.riskFlags().stream().distinct().toList());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String formatDebateSummary(String debateSummary) {
        if (debateSummary == null || debateSummary.isBlank()) return "无辩论记录";
        try {
            JSONObject obj = JSON.parseObject(debateSummary);
            return "Bull论据: " + obj.getString("bullArgument") +
                    "\nBear论据: " + obj.getString("bearArgument") +
                    "\n裁判结论: " + obj.getString("judgeReasoning");
        } catch (Exception e) {
            return debateSummary;
        }
    }
}
