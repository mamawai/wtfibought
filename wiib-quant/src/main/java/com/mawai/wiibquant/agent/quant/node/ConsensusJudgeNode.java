package com.mawai.wiibquant.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.mawai.wiibquant.agent.quant.domain.AgentVote;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.HorizonForecast;
import com.mawai.wiibquant.agent.quant.domain.MacroContext;
import com.mawai.wiibquant.agent.quant.judge.ConsensusForecast;
import com.mawai.wiibquant.agent.quant.judge.ConsensusJudge;
import com.mawai.wiibquant.agent.quant.judge.ConsensusJudge.Adjustment;
import com.mawai.wiibquant.agent.quant.judge.HorizonDecisionPolicy;
import com.mawai.wiibquant.agent.quant.memory.AgentPerformanceMemoryService.AgentStat;
import com.mawai.wiibquant.agent.quant.memory.MemoryService;
import com.mawai.wiibquant.agent.quant.service.FactorWeightOverrideService;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 新共识裁决节点：对 H6/H12/H24 三区间并行裁决。
 *
 * <p>与旧 run_judges 的关键区别：
 * <ul>
 *   <li>读 research_forecast（MacroContext）作为主票锚定方向</li>
 *   <li>evidence votes 作为辅助修正</li>
 *   <li>输出 ConsensusForecast（只有方向/置信度/分歧），不产出 entry/tp/sl</li>
 *   <li>旧 horizon_forecasts state key 仍写入但 entry/tp/sl 全部为 null/0 占位</li>
 * </ul>
 */
@Slf4j
public class ConsensusJudgeNode implements NodeAction {

    private static final String[] HORIZONS = {"H6", "H12", "H24"};

    private final MemoryService memoryService;
    private final FactorWeightOverrideService weightOverrideService;

    public ConsensusJudgeNode(MemoryService memoryService,
                               FactorWeightOverrideService weightOverrideService) {
        this.memoryService = memoryService;
        this.weightOverrideService = weightOverrideService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();

        // 读 research forecast 主票
        MacroContext researchForecast = (MacroContext) state.value("research_forecast")
                .orElse(state.value("macro_context").orElse(null));

        // 读 evidence votes（旧 key: agent_votes，过渡期兼容）
        List<AgentVote> evidenceVotes = (List<AgentVote>) state.value("agent_votes").orElse(List.of());
        FeatureSnapshot snapshot = (FeatureSnapshot) state.value("feature_snapshot").orElse(null);

        String symbol = (String) state.value("target_symbol").orElse("BTCUSDT");

        // 加载 agent 统计
        Map<String, Map<String, AgentStat>> agentStats = Map.of();
        if (memoryService != null) {
            try {
                agentStats = memoryService.getAgentFullStats(symbol, null);
                if (!agentStats.isEmpty()) {
                    log.info("[Q5.0] 加载agent统计 agents={}", agentStats.keySet());
                }
            } catch (Exception e) {
                log.warn("[Q5.0] agent统计查询失败: {}", e.getMessage());
            }
        }

        log.info("[Q5.0] consensus_judge开始 evidenceVotes={}", evidenceVotes.size());

        List<ConsensusForecast> forecasts = new ArrayList<>(3);
        List<Adjustment> allAdjustments = Collections.synchronizedList(new ArrayList<>());
        Map<String, Map<String, AgentStat>> finalStats = agentStats;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ConsensusForecast>> futures = new ArrayList<>(3);
            ConsensusJudge[] judges = new ConsensusJudge[3];

            for (int i = 0; i < HORIZONS.length; i++) {
                String horizonKey = HORIZONS[i];
                ForecastHorizon fh = ForecastHorizon.fromHours(
                        Integer.parseInt(horizonKey.substring(1)));

                // 从 research forecast 提取该 horizon 的方向
                MacroContext.Leg leg = researchForecast != null
                        ? researchForecast.legs().getOrDefault(fh, MacroContext.Leg.neutral())
                        : MacroContext.Leg.neutral();

                // 过滤该 horizon 的 evidence votes
                List<AgentVote> horizonVotes = evidenceVotes.stream()
                        .filter(v -> horizonKey.equals(v.horizon()))
                        .toList();

                judges[i] = new ConsensusJudge(horizonKey, leg.directionSign(),
                        leg.directionConfidence(), finalStats, weightOverrideService,
                        snapshot != null ? snapshot.regime() : null, null);
                ConsensusJudge judge = judges[i];
                futures.add(executor.submit(() -> judge.judge(horizonVotes)));
            }

            for (int i = 0; i < HORIZONS.length; i++) {
                try {
                    ConsensusForecast cf = futures.get(i).get(5, TimeUnit.SECONDS);
                    forecasts.add(cf);
                    allAdjustments.addAll(judges[i].getAdjustments());
                } catch (Exception e) {
                    log.warn("[Q5] ConsensusJudge[{}]裁决失败: {}", HORIZONS[i], e.getMessage());
                    forecasts.add(ConsensusForecast.noTrade(HORIZONS[i], 1.0));
                }
            }
        }

        for (ConsensusForecast f : forecasts) {
            log.info("[Q5.result] {} → {} conf={} disagree={}",
                    f.horizon(), f.direction(),
                    String.format("%.2f", f.confidence()), String.format("%.2f", f.disagreement()));
        }

        String overallDecision = HorizonDecisionPolicy.overallDecision(forecasts);
        String riskStatus = HorizonDecisionPolicy.overallRiskStatus(forecasts);
        String cycleId = "qf-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF))
                + "-" + symbol;

        log.info("[Q5.end] consensus_judge完成 decision={} riskStatus={} cycleId={} adjustments={} 耗时{}ms",
                overallDecision, riskStatus, cycleId, allAdjustments.size(),
                System.currentTimeMillis() - startMs);

        Map<String, Object> result = new HashMap<>();
        result.put("consensus_forecasts", forecasts);

        // 向后兼容：同时写 horizon_forecasts（旧 key），entry/tp/sl=null占位
        List<HorizonForecast> legacyForecasts = forecasts.stream()
                .map(cf -> new HorizonForecast(cf.horizon(), cf.direction(), cf.confidence(),
                        0, cf.disagreement(),
                        null, null, null, null, null, 0, 0))
                .toList();
        result.put("horizon_forecasts", legacyForecasts);

        result.put("overall_decision", overallDecision);
        result.put("risk_status", riskStatus);
        result.put("cycle_id", cycleId);
        if (!allAdjustments.isEmpty()) {
            result.put("memory_weight_adjustments", buildAdjustmentSummary(allAdjustments));
        }
        return result;
    }

    private List<Map<String, Object>> buildAdjustmentSummary(List<Adjustment> adjustments) {
        List<Map<String, Object>> list = new ArrayList<>(adjustments.size());
        for (Adjustment adj : adjustments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("horizon", adj.horizon());
            item.put("agent", adj.agent());
            item.put("base", round3(adj.baseWeight()));
            item.put("final", round3(adj.finalWeight()));
            item.put("multiplier", round2(adj.memoryMultiplier()));
            item.put("accuracy", round3(adj.accuracy()));
            item.put("samples", adj.sampleCount());
            list.add(item);
        }
        return list;
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
