package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibservice.agent.quant.domain.*;
import com.mawai.wiibservice.agent.quant.judge.HorizonJudge;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Bull vs Bear辩论裁决节点（3-call架构）。
 * 替代原MetaJudge+ConsistencyCheck：
 * 1. Bull辩手 ∥ Bear辩手：并行独立LLM调用，互不影响
 * 2. Judge裁判：综合原始数据+双方论据+历史记忆，做最终裁决
 * <p>
 * 安全约束继承自MetaJudge：
 * - confidence只能下调不能上调
 * - 方向翻转时cap到原始50%
 * - LLM失败fallback到原始裁决
 */
@Slf4j
public class DebateJudgeNode implements NodeAction {

    /**
     * 辩论裁决运行时开关。true=启用3-call辩论；false=跳过辩论返回中性概率(33/34/33)保留原始forecasts。
     * Phase 0A D7-B：默认关闭——3次LLM调用省掉，confidence不再被辩论调整，规避方差注入。
     * 类与workflow节点保留不动，Admin可运行时切回true做前后对比。
     */
    public static volatile boolean ENABLED = false;

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

        if (!ENABLED) {
            // D7-B短路：跳过3-call辩论，下发33/34/33中性概率；forecasts/decision/risk_status原样透传
            Map<String, Integer[]> defaultProbs = new HashMap<>();
            for (HorizonForecast f : forecasts) {
                defaultProbs.put(f.horizon(), new Integer[]{33, 34, 33});
            }
            log.info("[Q4.5.disabled] 辩论已禁用(D7-B), 保留原始forecasts, 下发中性概率 forecasts={}", forecasts.size());
            return Map.of("debate_probs", defaultProbs);
        }

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
            String dataContext = buildDataContext(forecasts, votes, snapshot,
                    overallDecision, riskStatus, regimeTransition);

            String memoryContext = "暂无历史记忆";
            if (memoryService != null) {
                try {
                    String regime = snapshot != null ? snapshot.regime().name() : "UNKNOWN";
                    memoryContext = memoryService.buildMemoryContext(
                            snapshot != null ? snapshot.symbol() : "BTCUSDT", regime);
                } catch (Exception e) {
                    log.warn("[Q4.5] 记忆查询失败: {}", e.getMessage());
                }
            }

            // Phase 1: Bull & Bear 并行辩论（独立上下文，互不影响）
            String bullArg;
            String bearArg;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<String> bullCf = CompletableFuture
                        .supplyAsync(() -> callMode.call(chatClient, buildBullPrompt(dataContext)), executor)
                        .orTimeout(60, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            log.warn("[Q4.5] Bull辩手调用失败: {}", ex.getMessage());
                            return "Bull辩手未能提供论据";
                        });
                CompletableFuture<String> bearCf = CompletableFuture
                        .supplyAsync(() -> callMode.call(chatClient, buildBearPrompt(dataContext)), executor)
                        .orTimeout(60, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            log.warn("[Q4.5] Bear辩手调用失败: {}", ex.getMessage());
                            return "Bear辩手未能提供论据";
                        });

                CompletableFuture.allOf(bullCf, bearCf).join();
                bullArg = bullCf.join();
                bearArg = bearCf.join();
            }
            log.info("[Q4.5.1] Bull({}chars) Bear({}chars) 辩论完成 耗时{}ms",
                    bullArg.length(), bearArg.length(), System.currentTimeMillis() - startMs);

            // Phase 2: Judge 裁决（综合数据 + 双方论据 + 历史记忆）
            String judgeResponse = callMode.call(chatClient,
                    buildJudgePrompt(dataContext, memoryContext, bullArg, bearArg));
            log.info("[Q4.5.2] Judge裁决 {}chars 总耗时{}ms",
                    judgeResponse != null ? judgeResponse.length() : 0,
                    System.currentTimeMillis() - startMs);

            return applyDebateResult(forecasts, judgeResponse, bullArg, bearArg, riskStatus, startMs);
        } catch (Exception e) {
            log.warn("[Q4.5] 辩论流程失败，保留原始裁决: {}", e.getMessage());
            return Map.of();
        }
    }

    // ==================== Prompt构建 ====================

    private String buildDataContext(List<HorizonForecast> forecasts,
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

        return """
                【系统裁决结果】
                %s
                【系统决策】%s
                【风控状态】%s

                【投票详情（按区间，5个Agent×3个区间）】
                %s
                【各Agent投票方向汇总】
                %s
                【市场状态】%s
                【Regime转换信号】%s
                【微结构快照】%s
                【数据质量】%s""".formatted(
                forecastBlock, overallDecision, riskStatus,
                voteBlock, agentSummaryBlock,
                regime, regimeTransition, microBlock, qualityText);
    }

    private String buildBullPrompt(String dataContext) {
        return """
                你是加密货币量化系统的做多辩手(Bull)。请基于以下数据，严格站在做多立场构建最强论据。

                %s

                要求：
                - 你的唯一目标是论证"应该做多"，不要替对方辩护，不要自我质疑
                - 引用具体的Agent投票数据（如"momentum在0-10给出LONG score=0.45"）
                - 引用微结构信号（如"盘口买盘主导 bidAskImbalance=0.25"）
                - 如果系统裁决是做空或NO_TRADE，论证为什么应该重新考虑做多
                - 对每个区间(0-10/10-20/20-30min)分别分析做多理由
                - 指出有利于多头的regime特征和转换信号

                限300字，纯文字论述，不要返回JSON。""".formatted(dataContext);
    }

    private String buildBearPrompt(String dataContext) {
        return """
                你是加密货币量化系统的做空辩手(Bear)。请基于以下数据，严格站在做空/观望立场构建最强论据。

                %s

                要求：
                - 你的唯一目标是论证"应该做空或观望"，不要替对方辩护，不要自我质疑
                - 引用具体的Agent投票数据（如"volatility在0-10给出SHORT score=-0.30"）
                - 引用微结构信号（如"tradeDelta为负=主动卖压"）
                - 指出系统裁决中可能被忽略的风险
                - 如果regime处于转换期(WEAKENING/BREAKING)，强调转换风险
                - 对每个区间(0-10/10-20/20-30min)分别分析做空/观望理由
                - 指出不利于多头的资金费率、持仓量、爆仓信号等

                限300字，纯文字论述，不要返回JSON。""".formatted(dataContext);
    }

    private String buildJudgePrompt(String dataContext, String memoryContext,
                                     String bullArgument, String bearArgument) {
        return """
                你是加密货币量化系统的裁判(Judge)。Bull辩手和Bear辩手已在完全隔离的环境中独立完成辩论。
                请综合原始数据、双方论据和历史记忆，做出最终裁决。

                ========== 原始数据 ==========
                %s

                【历史记忆】
                %s

                ========== 辩论论据 ==========
                【Bull辩手（做多方）】
                %s

                【Bear辩手（做空方）】
                %s

                ========== 裁决要求 ==========

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
                10. 期权IV信号：DVOL/ATM IV抬升但价格平静→隐含波动率定价上升；skew极端→方向性押注集中

                裁决原则：
                - 不要因为双方都有道理就默认NO_TRADE，要做出明确判断
                - 综合评估Bull和Bear的论据强度，谁的证据更具体、更有数据支撑
                - confidence只能下调不能上调（保守原则）
                - 如果需要翻转方向，新confidence不得超过原始的50%%
                - 如果Bull和Bear论据都很弱，维持系统原始裁决(approved=true)
                - direction只能是LONG/SHORT/NO_TRADE
                - 不修改entry/tp/sl价位

                概率修正：
                基于辩论中挖掘到的深层信息（如跨维度共振、资金流向矛盾、regime转换二阶信号等），
                对每个区间给出真实概率分布。三个概率之和必须等于100。

                严格返回JSON（不要markdown包裹）：
                {
                  "judgeReasoning": "裁判推理过程(200字内)",
                  "horizons": [
                    {"horizon":"0_10","approved":true,"newDirection":"LONG","newConfidence":0.65,"reason":"一句话",
                     "bullPct":45,"rangePct":35,"bearPct":20},
                    {"horizon":"10_20","approved":true,"newDirection":"NO_TRADE","newConfidence":0,"reason":"一句话",
                     "bullPct":30,"rangePct":40,"bearPct":30},
                    {"horizon":"20_30","approved":false,"newDirection":"NO_TRADE","newConfidence":0,"reason":"一句话",
                     "bullPct":25,"rangePct":45,"bearPct":30}
                  ]
                }
                """.formatted(dataContext, memoryContext, bullArgument, bearArgument);
    }

    // ==================== 结果应用（继承MetaJudge全部安全逻辑）====================

    private Map<String, Object> applyDebateResult(List<HorizonForecast> original,
                                                    String llmResponse,
                                                    String bullArg,
                                                    String bearArg,
                                                    String originalRiskStatus,
                                                    long startMs) {
        if (llmResponse == null || llmResponse.isBlank()) return Map.of();

        try {
            String json = JsonUtils.extractJson(llmResponse);
            JSONObject root = JSON.parseObject(json);

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
                String rawHorizon = h.getString("horizon");
                adjustMap.put(normalizeHorizon(rawHorizon), h);
            }

            List<HorizonForecast> adjusted = new ArrayList<>(3);
            for (HorizonForecast f : original) {
                JSONObject adj = adjustMap.get(f.horizon());
                if (adj == null || adj.getBooleanValue("approved", true)) {
                    // approved: 允许小幅调整 confidence（上调不超过+0.10）
                    double newConf = adj != null ? adj.getDoubleValue("newConfidence") : f.confidence();
                    double cappedConf = Math.min(newConf, f.confidence() + 0.10);
                    if (cappedConf > 0 && Math.abs(cappedConf - f.confidence()) > 1e-6) {
                        log.info("[Q4.5.merge] {} conf调整 {}→{}",
                                f.horizon(), fmt(f.confidence()), fmt(cappedConf));
                        adjusted.add(withConfidence(f, cappedConf));
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

                // NO_TRADE翻转时允许LLM提升置信度(cap 0.5)，其余允许小幅上调(+0.10)
                if (f.direction() != Direction.NO_TRADE) {
                    newConf = Math.min(newConf, f.confidence() + 0.10);
                } else {
                    newConf = Math.min(newConf, 0.5);
                }

                if (newDir == Direction.NO_TRADE || newConf <= 0) {
                    log.info("[Q4.5.merge] {} →NO_TRADE reason={}", f.horizon(), reason);
                    adjusted.add(HorizonForecast.noTrade(f.horizon(), f.disagreement()));
                } else if (newDir != f.direction()) {
                    // NO_TRADE翻转用已cap的conf，LONG↔SHORT翻转cap到原始50%
                    double cappedConf = f.direction() == Direction.NO_TRADE
                            ? newConf
                            : Math.min(newConf, f.confidence() * 0.5);
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

            // 解析辩论概率修正 → 传递给报告层
            Map<String, Integer[]> debateProbs = new HashMap<>();
            for (int i = 0; i < horizons.size(); i++) {
                JSONObject h = horizons.getJSONObject(i);
                String hz = normalizeHorizon(h.getString("horizon"));
                int bullPct = h.getIntValue("bullPct", -1);
                int bearPct = h.getIntValue("bearPct", -1);
                int rangePct = h.getIntValue("rangePct", -1);
                if (bullPct >= 0 && bearPct >= 0 && rangePct >= 0
                        && Math.abs(bullPct + bearPct + rangePct - 100) <= 3) {
                    // 归一化到100
                    int sum = bullPct + bearPct + rangePct;
                    if (sum != 100) {
                        int diff = 100 - sum;
                        rangePct += diff;
                    }
                    debateProbs.put(hz, new Integer[]{bullPct, rangePct, bearPct});
                    log.info("[Q4.5.prob] {} 辩论概率修正 bull={}% range={}% bear={}%",
                            hz, bullPct, rangePct, bearPct);
                }
            }
            if (!debateProbs.isEmpty()) {
                result.put("debate_probs", debateProbs);
            }

            log.info("[Q4.5.end] 辩论裁决完成 decision={} 耗时{}ms",
                    result.get("overall_decision"), System.currentTimeMillis() - startMs);
            return result;
        } catch (Exception e) {
            log.warn("[Q4.5] 辩论结果解析失败，保留原始裁决: {}", e.getMessage());
            // 返回默认debate_probs (均分)，避免下游缺失
            Map<String, Integer[]> defaultProbs = new HashMap<>();
            for (HorizonForecast f : original) {
                defaultProbs.put(f.horizon(), new Integer[]{33, 34, 33});
            }
            return Map.of("debate_probs", defaultProbs);
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
        String base = ("futuresBidAsk=%.3f spotBidAsk=%.3f tradeDelta=%.3f intensity=%.1f largeBias=%.3f oiChange=%.3f fundingDev=%.3f lsrExtreme=%.3f basis=%.2fbps spotLead=%.3f" +
                " liquidationPressure=%.3f(vol=%.0fUSDT) topTraderBias=%.3f takerPressure=%.3f" +
                " fearGreed=%d(%s)").formatted(
                snapshot.bidAskImbalance(), snapshot.spotBidAskImbalance(), snapshot.tradeDelta(),
                snapshot.tradeIntensity(), snapshot.largeTradeBias(),
                snapshot.oiChangeRate(),
                snapshot.fundingDeviation(), snapshot.lsrExtreme(), snapshot.spotPerpBasisBps(), snapshot.spotLeadLagScore(),
                snapshot.liquidationPressure(), snapshot.liquidationVolumeUsdt(),
                snapshot.topTraderBias(), snapshot.takerBuySellPressure(),
                snapshot.fearGreedIndex(), snapshot.fearGreedLabel());
        String iv = snapshot.toIvSummary();
        if (!"无数据".equals(iv)) base += " " + iv;
        return base;
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
        // NO_TRADE原始仓位为0，翻转后用基准值的一半（来源统一在HorizonJudge）
        int maxLev = f.maxLeverage();
        double maxPos = f.maxPositionPct();
        if (maxLev <= 0 || maxPos <= 0) {
            maxLev = Math.max(1, (HorizonJudge.getMaxLeverage(f.horizon()) + 1) / 2);
            maxPos = HorizonJudge.getBasePositionPct(f.horizon()) * 0.5;
        }
        return new HorizonForecast(f.horizon(), newDir, newConf, newScore, f.disagreement(),
                null, null, null, null, null, maxLev, maxPos);
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

    /**
     * 归一化 horizon 字符串：LLM 可能返回 "0-10min"/"0_10min"/"0_10" 等变体
     */
    private String normalizeHorizon(String raw) {
        if (raw == null) return "0_10";
        String s = raw.replaceAll("[^0-9_]", "");
        if (s.startsWith("0")) return "0_10";
        if (s.startsWith("10")) return "10_20";
        if (s.startsWith("20")) return "20_30";
        return raw;
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
