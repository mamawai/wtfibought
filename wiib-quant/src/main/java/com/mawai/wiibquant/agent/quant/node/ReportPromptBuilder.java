package com.mawai.wiibquant.agent.quant.node;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibquant.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibquant.agent.quant.domain.AgentVote;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.factor.NewsEventAgent;

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
        // 波动画像（主腿）——vol 经 BTC/ETH/SOL 样本外验证，是唯一稳定可靠的信号
        StringBuilder volBlock = new StringBuilder();
        if (hardReport.getVolProfile() != null && !hardReport.getVolProfile().isEmpty()) {
            for (CryptoAnalysisReport.VolProfile vp : hardReport.getVolProfile()) {
                volBlock.append("%s: 波动档=%s 预期幅度=%dbps 历史分位=%.0f%% 风险预算=%.2f\n"
                        .formatted(vp.getHorizon(), vp.getVolState(), vp.getExpectedMoveBps(),
                                vp.getTrailingPercentile() * 100.0, vp.getRiskBudgetHint()));
            }
        } else {
            volBlock.append("波动数据预热中\n");
        }

        String directionBlock = "短周期(H6/6h): %s\n中周期(H12/12h): %s\n长周期(H24/24h): %s"
                .formatted(hardReport.getDirection().getUltraShort(),
                        hardReport.getDirection().getShortTerm(),
                        hardReport.getDirection().getMid());

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
                你是加密货币波动与风险分析师。系统已完成全部结构化计算，请基于以下数据综合推理并撰写分析。

                【核心定位（务必遵守）】
                波动状态(vol)是经 BTC/ETH/SOL 样本外验证、唯一稳定可靠的信号——它是你分析的主线。
                方向(direction)的 edge 很弱、命中率接近随机，只能作低置信参考，严禁写成确定性预测。

                【波动画像（主）— 各周期 vol】
                %s
                【方向参考（弱 edge，仅供低置信参考，不可作为确定结论）】
                %s
                【关键价位】支撑: %s  阻力: %s

                【系统置信度】%d%%（注：这是方向置信，edge 弱，仅供参考）
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
                1. reasoning: 先解读波动状态与风险预算（可靠主线：当前波动处于什么档、风险预算多少、该收还是放），再附带说明方向信号的强弱与不确定性；分析各维度一致性与矛盾
                2. summary: 一句话——突出当前波动水平与风险（主），方向仅作辅助提示，不得把弱 edge 方向写成确定结论
                3. analysisBasis: 核心依据（2-3句话），以波动/风险画像为主
                4. indicators: 用通俗语言解读关键指标状态，关键术语首次出现时加简短注释如RSI=35（偏超卖）
                5. riskWarnings: 风险提示列表

                约束：
                - 必须诚实：方向 edge 弱，不得夸大方向确定性，不得新增系统未给出的概率、胜率或极端判断
                - 方向与风控约束已由量化系统确定，你只能解释不能修改
                - 裁决是观望/NO_TRADE 时必须如实写观望

                严格返回JSON（不要markdown包裹）：
                {
                  "reasoning": "综合推理分析",
                  "summary": "一句话总结",
                  "analysisBasis": "预测依据",
                  "indicators": "指标解读",
                  "riskWarnings": ["风险1", "风险2"]
                }
                """.formatted(
                volBlock.toString(), directionBlock,
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
