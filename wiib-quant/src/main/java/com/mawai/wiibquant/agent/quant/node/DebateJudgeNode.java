package com.mawai.wiibquant.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibquant.agent.quant.domain.*;
import com.mawai.wiibquant.agent.quant.domain.debate.WeakLean;
import com.mawai.wiibquant.agent.quant.domain.output.DebateHorizonResponse;
import com.mawai.wiibquant.agent.quant.domain.output.DebateJudgeResponse;
import com.mawai.wiibquant.agent.quant.judge.ConsensusForecast;
import com.mawai.wiibquant.agent.quant.judge.HorizonDecisionPolicy;
import com.mawai.wiibquant.agent.quant.memory.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

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
 * - confidence允许小幅上调（+0.10 cap）
 * - 方向翻转时cap到原始50%
 * - LLM失败fallback到原始裁决
 */
@Slf4j
public class DebateJudgeNode implements NodeAction {

    private static final BeanOutputConverter<DebateJudgeResponse> JUDGE_CONVERTER =
            new BeanOutputConverter<>(DebateJudgeResponse.class);

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
        MacroContext macroContext = (MacroContext) state.value("macro_context").orElse(null);

        log.info("[Q4.5.0] debate_judge开始 mode=LIVE forecasts={} votes={} decision={}",
                forecasts.size(), votes.size(), overallDecision);

        if (forecasts.isEmpty()) return Map.of();

        try {
            String dataContext = buildDataContext(forecasts, votes, snapshot,
                    overallDecision, riskStatus, regimeTransition, macroContext);

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
                                     String regimeTransition,
                                     MacroContext macroContext) {
        String forecastBlock = buildForecastBlock(forecasts);
        String voteBlock = buildVoteBlock(votes);
        String agentSummaryBlock = buildAgentSummaryBlock(votes);
        String regime = snapshot != null ? snapshot.regime().name() : "UNKNOWN";
        String microBlock = buildMicroBlock(snapshot);
        String qualityText = buildQualityText(snapshot);
        String macroBlock = macroContext != null && !macroContext.toDebateBlock().isBlank()
                ? macroContext.toDebateBlock() : "无";

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
                【宏观上下文】%s
                【微结构快照】%s
                【数据质量】%s""".formatted(
                forecastBlock, overallDecision, riskStatus,
                voteBlock, agentSummaryBlock,
                regime, regimeTransition, macroBlock, microBlock, qualityText);
    }

    private String buildBullPrompt(String dataContext) {
        return """
                你是加密货币量化系统的做多辩手(Bull)。请基于以下数据，严格站在做多立场构建最强论据。

                %s

                要求：
                - 你的唯一目标是论证"应该做多"，不要替对方辩护，不要自我质疑
                - 引用具体的Agent投票数据（如"momentum在H6给出LONG score=0.45"）
                - 引用微结构信号（如"盘口买盘主导 bidAskImbalance=0.25"）
                - 如果系统裁决是做空或NO_TRADE，论证为什么应该重新考虑做多
                - 对每个区间(H6/H12/H24)分别分析做多理由
                - 指出有利于多头的regime特征和转换信号

                限300字，纯文字论述，不要返回JSON。""".formatted(dataContext);
    }

    private String buildBearPrompt(String dataContext) {
        return """
                你是加密货币量化系统的做空辩手(Bear)。请基于以下数据，严格站在做空/观望立场构建最强论据。

                %s

                要求：
                - 你的唯一目标是论证"应该做空或观望"，不要替对方辩护，不要自我质疑
                - 引用具体的Agent投票数据（如"volatility在H6给出SHORT score=-0.30"）
                - 引用微结构信号（如"tradeDelta为负=主动卖压"）
                - 指出系统裁决中可能被忽略的风险
                - 如果regime处于转换期(WEAKENING/BREAKING)，强调转换风险
                - 对每个区间(H6/H12/H24)分别分析做空/观望理由
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
                2. 跨区间逻辑：H6看多但H12/H24看空是否合理？长周期背景是否支持短周期方向？
                3. Regime适应：SQUEEZE/SHOCK下方向和置信度是否足够保守？
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
                - confidence允许小幅上调（+0.10 cap）
                - 如果需要翻转方向，新confidence不得超过原始的50%%
                - 如果Bull和Bear论据都很弱，维持系统原始裁决(approved=true)
                - direction只能是LONG/SHORT/NO_TRADE
                - 不生成entry/tp/sl/leverage/position；仓位和杠杆由后续RiskGate生成

                概率修正：
                基于辩论中挖掘到的深层信息（如跨维度共振、资金流向矛盾、regime转换二阶信号等），
                对每个区间给出真实概率分布。三个概率之和必须等于100。

                弱方向 lean 与失效条件（每区间必填）：
                - newDirection 在此同时视作"低置信弱 lean"，非硬核预测；信号矛盾时给 NO_TRADE = 无方向态（大方承认看不清，不硬挤方向）
                - consequence：一句话后果叙事，"若X兑现 → 未来Yh可能Z"
                - invalidation：一句话反事实失效条件，必须可证伪，"若A则本 lean 作废"

                严格返回JSON（不要markdown包裹）：
                {
                  "judgeReasoning": "裁判推理过程(200字内)",
                  "horizons": [
                    {"horizon":"H6","approved":true,"newDirection":"LONG","newConfidence":0.65,"reason":"一句话",
                     "bullPct":45,"rangePct":35,"bearPct":20,
                     "consequence":"若多头拥挤兑现，12h内或快速回落","invalidation":"funding回正且OI回落则本lean作废"},
                    {"horizon":"H12","approved":true,"newDirection":"NO_TRADE","newConfidence":0,"reason":"一句话",
                     "bullPct":30,"rangePct":40,"bearPct":30,
                     "consequence":"信号分歧，方向不明","invalidation":"出现单边放量突破则重估"},
                    {"horizon":"H24","approved":false,"newDirection":"NO_TRADE","newConfidence":0,"reason":"一句话",
                     "bullPct":25,"rangePct":45,"bearPct":30,
                     "consequence":"宏观背景偏空但未确认","invalidation":"站稳关键阻力则转中性"}
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
        if (llmResponse == null || llmResponse.isBlank()) {
            return Map.of();
        }

        try {
            String json = JsonUtils.extractJson(llmResponse);
            DebateJudgeResponse output = JUDGE_CONVERTER.convert(json);

            String judgeReasoning = output.judgeReasoning();
            List<DebateHorizonResponse> horizons = output.horizons();

            log.info("[Q4.5.debate] Bull: {} | Bear: {} | Judge: {}",
                    truncate(bullArg, 80), truncate(bearArg, 80), truncate(judgeReasoning, 80));

            if (horizons == null || horizons.size() != 3) {
                log.warn("[Q4.5] horizons数组异常(size={}), 保留原始",
                        horizons != null ? horizons.size() : 0);
                return buildDebateSummaryOnly(bullArg, bearArg, judgeReasoning);
            }

            // 应用调整（逻辑继承自MetaJudge.applyAdjustments）
            Map<String, DebateHorizonResponse> adjustMap = new HashMap<>();
            for (DebateHorizonResponse h : horizons) {
                adjustMap.put(normalizeHorizon(h.horizon()), h);
            }

            List<HorizonForecast> adjusted = new ArrayList<>(3);
            for (HorizonForecast f : original) {
                DebateHorizonResponse adj = adjustMap.get(f.horizon());
                // approved 缺省视作 true（保留原 getBooleanValue("approved", true) 语义）
                boolean approved = adj == null || adj.approved() == null || adj.approved();
                if (approved) {
                    // approved: 允许小幅调整 confidence（上调不超过+0.10）
                    // newConfidence 缺失时用 f.confidence 兜底，cap 后差异为 0 不会触发调整，等价原 0.0 兜底行为
                    double newConf = (adj != null && adj.newConfidence() != null)
                            ? adj.newConfidence() : f.confidence();
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
                Direction newDir = parseDirection(adj.newDirection(), f.direction());
                double newConf = adj.newConfidence() != null ? adj.newConfidence() : 0.0;
                String reason = adj.reason();

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

            // 解析辩论概率修正 → 传递给报告层
            Map<String, Integer[]> debateProbs = new HashMap<>();
            for (DebateHorizonResponse h : horizons) {
                String hz = normalizeHorizon(h.horizon());
                Integer bullPctBox = h.bullPct();
                Integer bearPctBox = h.bearPct();
                Integer rangePctBox = h.rangePct();
                // 任一字段缺失即跳过（保留原 getIntValue("...", -1) 哨兵语义）
                if (bullPctBox == null || bearPctBox == null || rangePctBox == null) continue;
                int bullPct = bullPctBox;
                int bearPct = bearPctBox;
                int rangePct = rangePctBox;
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

            String rebuiltDecision = HorizonDecisionPolicy.overallDecision(toConsensus(adjusted));
            String rebuiltRiskStatus = rebuildRiskStatus(adjusted, originalRiskStatus);

            // Step 3：弱 lean 简报产物（展示层，与方向裁决解耦；下游暂不消费，Step 7 总装上简报）
            List<WeakLean> weakLeans = new ArrayList<>(horizons.size());
            for (DebateHorizonResponse h : horizons) {
                weakLeans.add(WeakLean.from(normalizeHorizon(h.horizon()), h.newDirection(),
                        h.bullPct(), h.rangePct(), h.bearPct(), h.consequence(), h.invalidation()));
            }

            Map<String, Object> result = new HashMap<>();
            result.put("horizon_forecasts", adjusted);
            result.put("overall_decision", rebuiltDecision);
            result.put("risk_status", rebuiltRiskStatus);
            result.put("debate_summary", buildDebateSummaryJson(bullArg, bearArg, judgeReasoning));
            result.put("weak_leans", weakLeans);
            if (!debateProbs.isEmpty()) {
                result.put("debate_probs", debateProbs);
            }

            log.info("[Q4.5.end] 辩论裁决完成 decision={} 耗时{}ms",
                    rebuiltDecision, System.currentTimeMillis() - startMs);
            return result;
        } catch (Exception e) {
            log.warn("[Q4.5] 辩论结果解析失败，保留原始裁决: {}", e.getMessage());
            // 返回默认debate_probs (均分)，避免下游缺失
            Map<String, Integer[]> defaultProbs = buildDefaultProbs(original);
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
        for (String horizon : List.of("H6", "H12", "H24")) {
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

    /** disabled/live-fallback 的正式报告概率，保持原 D7-B 中性行为。 */
    private Map<String, Integer[]> buildDefaultProbs(List<HorizonForecast> forecasts) {
        Map<String, Integer[]> defaultProbs = new HashMap<>();
        for (HorizonForecast f : forecasts) {
            defaultProbs.put(f.horizon(), new Integer[]{33, 34, 33});
        }
        return defaultProbs;
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
        // 预测层不产 sizing：leverage/position 占位 0，统一由 RiskGate 按 research 基准生成；
        // 翻转更保守已通过压低后的 newConf 传导。
        return new HorizonForecast(f.horizon(), newDir, newConf, newScore, f.disagreement(),
                null, null, null, null, null, 0, 0);
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
     * 归一化 horizon 字符串：主格式是 H6/H12/H24；旧格式只做历史兼容。
     */
    private String normalizeHorizon(String raw) {
        if (raw == null) return "H6";
        String s = raw.toUpperCase().replaceAll("[^H0-9]", "");
        if (s.contains("H6")) return "H6";
        if (s.contains("H12")) return "H12";
        if (s.contains("H24")) return "H24";
        // 旧格式兼容
        if (raw.contains("0_10") || raw.startsWith("0")) return "H6";
        if (raw.contains("10_20") || raw.startsWith("10")) return "H12";
        if (raw.contains("20_30") || raw.startsWith("20")) return "H24";
        return raw;
    }

    /** 把 debate 调整后的 HorizonForecast 投影成 ConsensusForecast，供 HorizonDecisionPolicy 复用同口径决策。 */
    private static List<ConsensusForecast> toConsensus(List<HorizonForecast> forecasts) {
        return forecasts.stream()
                .map(f -> new ConsensusForecast(f.horizon(), f.direction(), f.confidence(), f.disagreement()))
                .toList();
    }

    private String rebuildRiskStatus(List<HorizonForecast> adjusted, String original) {
        // 基础风险档复用 HorizonDecisionPolicy（与 ConsensusJudge 同口径），再合并上游 risk_status 不丢状态。
        String base = HorizonDecisionPolicy.overallRiskStatus(toConsensus(adjusted));
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        if (original != null) {
            for (String p : original.split(",")) {
                if (!p.isBlank()) parts.add(p.trim());
            }
        }
        parts.add(base);
        if (parts.size() > 1) { parts.remove("NORMAL"); parts.remove("UNKNOWN"); }
        return parts.isEmpty() ? "NORMAL" : String.join(",", parts);
    }

    private String fmtHorizon(String h) {
        return switch (h) { case "H6" -> "H6(6h)"; case "H12" -> "H12(12h)"; case "H24" -> "H24(24h)"; default -> h; };
    }

    private String fmtHorizonShort(String h) {
        return switch (h) { case "H6" -> "H6"; case "H12" -> "H12"; case "H24" -> "H24"; default -> h; };
    }

    private String fmt(double v) { return String.format("%.2f", v); }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
