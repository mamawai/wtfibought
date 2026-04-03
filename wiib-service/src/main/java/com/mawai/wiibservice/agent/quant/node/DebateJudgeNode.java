package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibservice.agent.quant.domain.*;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.*;

/**
 * Bull vs Bear辩论裁决节点。
 * 替代原MetaJudge+ConsistencyCheck，单次LLM调用中完成三角色辩论：
 * 1. Bull辩手：从投票和指标中构建做多论证
 * 2. Bear辩手：从投票和指标中构建做空论证
 * 3. Judge裁判：综合辩论+历史教训，审核系统裁决
 *
 * 安全约束继承自MetaJudge：
 * - confidence只能下调不能上调
 * - 方向翻转时cap到原始50%
 * - LLM失败fallback到原始裁决
 */
@Slf4j
public class DebateJudgeNode implements NodeAction {

    private final ChatClient chatClient;
    private final LlmCallMode callMode;
    private final MemoryService memoryService;

    public DebateJudgeNode(ChatClient.Builder builder, LlmCallMode callMode, MemoryService memoryService) {
        this.chatClient = builder.build();
        this.callMode = callMode;
        this.memoryService = memoryService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        List<HorizonForecast> forecasts =
                (List<HorizonForecast>) state.value("horizon_forecasts").orElse(List.of());
        List<AgentVote> votes =
                (List<AgentVote>) state.value("agent_votes").orElse(List.of());
        FeatureSnapshot snapshot =
                (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        String overallDecision = (String) state.value("overall_decision").orElse("FLAT");
        String riskStatus = (String) state.value("risk_status").orElse("UNKNOWN");
        String regimeTransition = (String) state.value("regime_transition").orElse("NONE");

        log.info("[Q4.5.0] debate_judge开始 forecasts={} votes={} decision={}",
                forecasts.size(), votes.size(), overallDecision);

        if (forecasts.isEmpty()) return Map.of();

        try {
            String prompt = buildDebatePrompt(forecasts, votes, snapshot,
                    overallDecision, riskStatus, regimeTransition);
            String response = callMode.call(chatClient, prompt);
            log.info("[Q4.5.1] 辩论LLM返回 {}chars 耗时{}ms",
                    response != null ? response.length() : 0, System.currentTimeMillis() - startMs);

            return applyDebateResult(forecasts, response, riskStatus, startMs);
        } catch (Exception e) {
            log.warn("[Q4.5] 辩论LLM失败，保留原始裁决: {}", e.getMessage());
            return Map.of();
        }
    }

    // ==================== Prompt构建 ====================

    private String buildDebatePrompt(List<HorizonForecast> forecasts,
                                      List<AgentVote> votes,
                                      FeatureSnapshot snapshot,
                                      String overallDecision,
                                      String riskStatus,
                                      String regimeTransition) {
        String forecastBlock = buildForecastBlock(forecasts);
        String voteBlock = buildVoteBlock(votes);
        String agentSummaryBlock = buildAgentSummaryBlock(votes);
        String regime = snapshot != null ? snapshot.regime().name() : "UNKNOWN";
        String microBlock = buildMicroBlock(snapshot);
        String qualityText = buildQualityText(snapshot);

        // 记忆注入
        String memoryContext = "暂无历史记忆";
        if (memoryService != null) {
            try {
                memoryContext = memoryService.buildMemoryContext(
                        snapshot != null ? snapshot.symbol() : "BTCUSDT", regime);
            } catch (Exception e) {
                log.warn("[Q4.5] 记忆查询失败: {}", e.getMessage());
            }
        }

        return """
                你是加密货币量化系统的辩论裁决员。系统已通过5个因子Agent投票和3个区间裁决器得出以下结果。
                你需要依次扮演3个角色完成辩论，然后给出最终裁决。

                【系统裁决结果】
                %s
                【系统决策】%s
                【风控状态】%s

                【15票投票详情（按区间）】
                %s
                【各Agent投票方向汇总】
                %s
                【市场状态】%s
                【Regime转换信号】%s
                【微结构快照】%s
                【数据质量】%s

                【历史记忆】
                %s

                ===== 第一步：BULL（做多辩手）=====
                你现在是Bull辩手。请站在做多立场，从以上所有数据中找出支持做多的最强证据。
                - 引用具体的Agent投票数据（如"momentum在0-10给出LONG score=0.45"）
                - 引用微结构信号（如"盘口买盘主导 bidAskImbalance=0.25"）
                - 如果系统裁决是做空或NO_TRADE，论证为什么应该重新考虑做多
                - 反驳可能的看空理由
                限200字。

                ===== 第二步：BEAR（做空辩手）=====
                你现在是Bear辩手。请站在做空立场，从以上所有数据中找出支持做空/观望的最强证据。
                - 引用具体的Agent投票数据
                - 引用微结构信号（如"tradeDelta为负=主动卖"）
                - 指出系统裁决中可能被忽略的风险
                - 如果regime处于转换期(WEAKENING/BREAKING)，强调转换风险
                - 反驳Bull辩手的论点
                限200字。

                ===== 第三步：JUDGE（裁判裁决）=====
                你现在是裁判。综合Bull和Bear的论据，结合历史记忆中的反思教训，对系统裁决做最终审核。

                审核维度：
                1. 方向矛盾：各区间裁决方向是否与多数Agent投票一致？
                2. 跨区间逻辑：0-10看多但10-20看空是否合理？
                3. Regime适应：SQUEEZE/SHOCK下仓位和杠杆是否足够保守？
                4. 微结构背离：做多但盘口卖压(bid<0)？做空但盘口买压(bid>0)？
                5. 转换风险：regime转换信号下是否需要更保守？
                6. 历史教训：记忆中的偏差提示是否适用于当前场景？
                7. 情绪极端：fearGreed<=20(极度恐惧)时做空要警惕反弹，>=80(极度贪婪)时做多要警惕回调
                8. 爆仓信号：大量多头爆仓(liquidationPressure>0.5)可能是下跌末端，大量空头爆仓(<-0.5)可能是上涨末端
                9. 聪明钱方向：topTraderBias与系统方向背离时需降低confidence

                裁决原则：
                - 不要因为双方都有道理就默认NO_TRADE，要做出明确判断
                - confidence只能下调不能上调（保守原则）
                - 如果需要翻转方向，新confidence不得超过原始的50%%
                - 如果Bull和Bear论据都很弱，维持系统原始裁决(approved=true)
                - direction只能是LONG/SHORT/NO_TRADE
                - 不修改entry/tp/sl价位

                严格返回JSON（不要markdown包裹）：
                {
                  "bullArgument": "Bull辩手论据摘要(100字内)",
                  "bearArgument": "Bear辩手论据摘要(100字内)",
                  "judgeReasoning": "裁判推理过程(150字内)",
                  "horizons": [
                    {"horizon":"0_10","approved":true,"newDirection":"LONG","newConfidence":0.65,"reason":"一句话"},
                    {"horizon":"10_20","approved":true,"newDirection":"NO_TRADE","newConfidence":0,"reason":"一句话"},
                    {"horizon":"20_30","approved":false,"newDirection":"NO_TRADE","newConfidence":0,"reason":"一句话"}
                  ]
                }
                """.formatted(
                forecastBlock, overallDecision, riskStatus,
                voteBlock, agentSummaryBlock,
                regime, regimeTransition, microBlock, qualityText,
                memoryContext);
    }

    // ==================== 结果应用（继承MetaJudge全部安全逻辑）====================

    private Map<String, Object> applyDebateResult(List<HorizonForecast> original,
                                                    String llmResponse,
                                                    String originalRiskStatus,
                                                    long startMs) {
        if (llmResponse == null || llmResponse.isBlank()) return Map.of();

        try {
            String json = JsonUtils.extractJson(llmResponse);
            JSONObject root = JSON.parseObject(json);

            String bullArg = root.getString("bullArgument");
            String bearArg = root.getString("bearArgument");
            String judgeReasoning = root.getString("judgeReasoning");
            JSONArray horizons = root.getJSONArray("horizons");

            log.info("[Q4.5.debate] Bull: {} | Bear: {} | Judge: {}",
                    truncate(bullArg, 80), truncate(bearArg, 80), truncate(judgeReasoning, 80));

            if (horizons == null || horizons.size() != 3) {
                log.warn("[Q4.5] horizons数组异常(size={}), 保留原始",
                        horizons != null ? horizons.size() : 0);
                return buildDebateSummaryOnly(bullArg, bearArg, judgeReasoning);
            }

            // 应用调整（逻辑继承自MetaJudge.applyAdjustments）
            Map<String, JSONObject> adjustMap = new HashMap<>();
            for (int i = 0; i < horizons.size(); i++) {
                JSONObject h = horizons.getJSONObject(i);
                adjustMap.put(h.getString("horizon"), h);
            }

            List<HorizonForecast> adjusted = new ArrayList<>(3);
            for (HorizonForecast f : original) {
                JSONObject adj = adjustMap.get(f.horizon());
                if (adj == null || adj.getBooleanValue("approved", true)) {
                    // approved: 检查是否仅下调confidence
                    double newConf = adj != null ? adj.getDoubleValue("newConfidence") : f.confidence();
                    if (newConf > 0 && newConf < f.confidence()) {
                        log.info("[Q4.5.merge] {} conf下调 {}→{}",
                                f.horizon(), fmt(f.confidence()), fmt(newConf));
                        adjusted.add(withConfidence(f, newConf));
                    } else {
                        adjusted.add(f);
                    }
                    continue;
                }

                // 未approved，应用调整
                String newDirStr = adj.getString("newDirection");
                Direction newDir = parseDirection(newDirStr, f.direction());
                double newConf = adj.getDoubleValue("newConfidence");
                String reason = adj.getString("reason");

                // 保守原则：confidence只能下调
                newConf = Math.min(newConf, f.confidence());

                if (newDir == Direction.NO_TRADE || newConf <= 0) {
                    log.info("[Q4.5.merge] {} →NO_TRADE reason={}", f.horizon(), reason);
                    adjusted.add(HorizonForecast.noTrade(f.horizon(), f.disagreement()));
                } else if (newDir != f.direction()) {
                    // 方向翻转：cap到原始50%
                    double cappedConf = Math.min(newConf, f.confidence() * 0.5);
                    log.info("[Q4.5.merge] {} 翻转 {}→{} conf={} reason={}",
                            f.horizon(), f.direction(), newDir, fmt(cappedConf), reason);
                    adjusted.add(rebuildForecast(f, newDir, cappedConf));
                } else {
                    log.info("[Q4.5.merge] {} 保持{} conf→{} reason={}",
                            f.horizon(), newDir, fmt(newConf), reason);
                    adjusted.add(withConfidence(f, newConf));
                }
            }

            // 构建输出
            Map<String, Object> result = new HashMap<>();
            result.put("horizon_forecasts", adjusted);
            result.put("overall_decision", rebuildDecision(adjusted));
            result.put("risk_status", rebuildRiskStatus(adjusted, originalRiskStatus));
            result.put("debate_summary", buildDebateSummaryJson(bullArg, bearArg, judgeReasoning));

            log.info("[Q4.5.end] 辩论裁决完成 decision={} 耗时{}ms",
                    result.get("overall_decision"), System.currentTimeMillis() - startMs);
            return result;
        } catch (Exception e) {
            log.warn("[Q4.5] 辩论结果解析失败，保留原始裁决: {}", e.getMessage());
            return Map.of();
        }
    }

    // ==================== 辅助方法 ====================

    private String buildForecastBlock(List<HorizonForecast> forecasts) {
        StringBuilder sb = new StringBuilder();
        for (HorizonForecast f : forecasts) {
            sb.append(fmtHorizon(f.horizon())).append(": ")
                    .append(f.direction()).append(" conf=").append(fmt(f.confidence()))
                    .append(" disagree=").append(fmt(f.disagreement()))
                    .append(" score=").append(String.format("%.3f", f.weightedScore()))
                    .append(" lev=").append(f.maxLeverage()).append("x")
                    .append(" pos=").append(String.format("%.1f%%", f.maxPositionPct() * 100))
                    .append("\n");
        }
        return sb.toString();
    }

    private String buildVoteBlock(List<AgentVote> votes) {
        StringBuilder sb = new StringBuilder();
        for (String horizon : List.of("0_10", "10_20", "20_30")) {
            sb.append("--- ").append(fmtHorizon(horizon)).append(" ---\n");
            for (AgentVote v : votes) {
                if (!horizon.equals(v.horizon())) continue;
                sb.append("  ").append(v.agent()).append(": ")
                        .append(v.direction()).append(" score=").append(fmt(v.score()))
                        .append(" conf=").append(fmt(v.confidence()));
                if (!v.reasonCodes().isEmpty()) sb.append(" reasons=").append(v.reasonCodes());
                if (!v.riskFlags().isEmpty()) sb.append(" flags=").append(v.riskFlags());
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String buildAgentSummaryBlock(List<AgentVote> votes) {
        Map<String, List<AgentVote>> byAgent = new LinkedHashMap<>();
        for (AgentVote v : votes) {
            byAgent.computeIfAbsent(v.agent(), k -> new ArrayList<>()).add(v);
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : byAgent.entrySet()) {
            sb.append(entry.getKey()).append(": ");
            for (AgentVote v : entry.getValue()) {
                sb.append(fmtHorizonShort(v.horizon())).append("=")
                        .append(v.direction()).append("(").append(fmt(v.score())).append(") ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildMicroBlock(FeatureSnapshot snapshot) {
        if (snapshot == null) return "无";
        return ("bidAskImbalance=%.3f tradeDelta=%.3f oiChange=%.3f fundingDev=%.3f lsrExtreme=%.3f" +
                " liquidationPressure=%.3f(vol=%.0fUSDT) topTraderBias=%.3f takerPressure=%.3f" +
                " fearGreed=%d(%s)").formatted(
                snapshot.bidAskImbalance(), snapshot.tradeDelta(), snapshot.oiChangeRate(),
                snapshot.fundingDeviation(), snapshot.lsrExtreme(),
                snapshot.liquidationPressure(), snapshot.liquidationVolumeUsdt(),
                snapshot.topTraderBias(), snapshot.takerBuySellPressure(),
                snapshot.fearGreedIndex(), snapshot.fearGreedLabel());
    }

    private String buildQualityText(FeatureSnapshot snapshot) {
        if (snapshot == null || snapshot.qualityFlags() == null || snapshot.qualityFlags().isEmpty())
            return "正常";
        return String.join(", ", snapshot.qualityFlags());
    }

    private String buildDebateSummaryJson(String bull, String bear, String judge) {
        JSONObject obj = new JSONObject();
        obj.put("bullArgument", bull != null ? bull : "");
        obj.put("bearArgument", bear != null ? bear : "");
        obj.put("judgeReasoning", judge != null ? judge : "");
        return obj.toJSONString();
    }

    private Map<String, Object> buildDebateSummaryOnly(String bull, String bear, String judge) {
        Map<String, Object> result = new HashMap<>();
        result.put("debate_summary", buildDebateSummaryJson(bull, bear, judge));
        return result;
    }

    private HorizonForecast withConfidence(HorizonForecast f, double newConf) {
        return new HorizonForecast(f.horizon(), f.direction(), newConf,
                f.weightedScore(), f.disagreement(),
                f.entryLow(), f.entryHigh(), f.invalidationPrice(),
                f.tp1(), f.tp2(), f.maxLeverage(), f.maxPositionPct());
    }

    private HorizonForecast rebuildForecast(HorizonForecast f, Direction newDir, double newConf) {
        double newScore = newDir == Direction.LONG ? Math.abs(f.weightedScore()) : -Math.abs(f.weightedScore());
        if (f.entryLow() != null && f.tp1() != null) {
            return new HorizonForecast(f.horizon(), newDir, newConf, newScore, f.disagreement(),
                    f.entryLow(), f.entryHigh(),
                    newDir == Direction.LONG ? f.tp1() : f.invalidationPrice(),
                    newDir == Direction.LONG ? f.invalidationPrice() : f.tp1(),
                    newDir == Direction.LONG
                            ? (f.tp2() != null ? f.invalidationPrice().subtract(f.invalidationPrice().subtract(f.tp2()).abs()) : null)
                            : f.tp2(),
                    f.maxLeverage(), f.maxPositionPct());
        }
        return new HorizonForecast(f.horizon(), newDir, newConf, newScore, f.disagreement(),
                null, null, null, null, null, f.maxLeverage(), f.maxPositionPct());
    }

    private Direction parseDirection(String str, Direction fallback) {
        if (str == null) return fallback;
        return switch (str.toUpperCase().trim()) {
            case "LONG" -> Direction.LONG;
            case "SHORT" -> Direction.SHORT;
            case "NO_TRADE" -> Direction.NO_TRADE;
            default -> fallback;
        };
    }

    private String rebuildDecision(List<HorizonForecast> forecasts) {
        HorizonForecast best = null;
        for (HorizonForecast f : forecasts) {
            if (f.direction() == Direction.NO_TRADE) continue;
            if (best == null || f.confidence() > best.confidence()) best = f;
        }
        return best == null ? "FLAT" : "PRIORITIZE_" + best.horizon() + "_" + best.direction().name();
    }

    private String rebuildRiskStatus(List<HorizonForecast> adjusted, String original) {
        long noTradeCount = adjusted.stream().filter(f -> f.direction() == Direction.NO_TRADE).count();
        double maxDisagree = adjusted.stream().mapToDouble(HorizonForecast::disagreement).max().orElse(0);

        LinkedHashSet<String> parts = new LinkedHashSet<>();
        if (original != null) {
            for (String p : original.split(",")) {
                if (!p.isBlank()) parts.add(p.trim());
            }
        }
        if (noTradeCount == adjusted.size()) parts.add("ALL_NO_TRADE");
        else if (maxDisagree >= 0.35) parts.add("HIGH_DISAGREEMENT");

        if (parts.size() > 1) { parts.remove("NORMAL"); parts.remove("UNKNOWN"); }
        return parts.isEmpty() ? "NORMAL" : String.join(",", parts);
    }

    private String fmtHorizon(String h) {
        return switch (h) { case "0_10" -> "0-10min"; case "10_20" -> "10-20min"; case "20_30" -> "20-30min"; default -> h; };
    }

    private String fmtHorizonShort(String h) {
        return switch (h) { case "0_10" -> "0-10"; case "10_20" -> "10-20"; case "20_30" -> "20-30"; default -> h; };
    }

    private String fmt(double v) { return String.format("%.2f", v); }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
