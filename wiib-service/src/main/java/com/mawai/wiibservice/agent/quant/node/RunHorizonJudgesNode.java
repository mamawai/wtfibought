package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.mawai.wiibservice.agent.quant.domain.*;
import com.mawai.wiibservice.agent.quant.judge.ConsensusBuilder;
import com.mawai.wiibservice.agent.quant.judge.HorizonJudge;
import com.mawai.wiibservice.agent.quant.judge.HorizonJudge.WeightAdjustment;
import com.mawai.wiibservice.agent.quant.memory.AgentPerformanceMemoryService.AgentStat;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import com.mawai.wiibservice.agent.quant.service.FactorWeightOverrideService;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 3个HorizonJudge并行裁决节点。
 * 内部虚拟线程并行执行，每个Judge只消费属于自己区间的投票。
 */
@Slf4j
public class RunHorizonJudgesNode implements NodeAction {

    private static final String[] HORIZONS = {"0_10", "10_20", "20_30"};
    private final MemoryService memoryService;
    private final FactorWeightOverrideService weightOverrideService;

    public RunHorizonJudgesNode(MemoryService memoryService, FactorWeightOverrideService weightOverrideService) {
        this.memoryService = memoryService;
        this.weightOverrideService = weightOverrideService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        List<AgentVote> allVotes = (List<AgentVote>) state.value("agent_votes").orElse(List.of());
        FeatureSnapshot snapshot = (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        BigDecimal lastPrice = snapshot != null ? snapshot.lastPrice() : null;
        List<String> qualityFlags = snapshot != null ? snapshot.qualityFlags() : List.of();
        String symbol = snapshot != null ? snapshot.symbol() : "BTCUSDT";
        MarketRegime regime = snapshot != null ? snapshot.regime() : null;

        // 加载完整统计（含 sample_count），供调权可见性
        Map<String, Map<String, AgentStat>> agentStats = Map.of();
        if (memoryService != null) {
            try {
                agentStats = memoryService.getAgentFullStats(symbol, regime != null ? regime.name() : null);
                if (!agentStats.isEmpty()) {
                    log.info("[Q4.0] 加载agent统计 agents={}", agentStats.keySet());
                }
            } catch (Exception e) {
                log.warn("[Q4.0] agent统计查询失败: {}", e.getMessage());
            }
        }

        log.info("[Q4.0] run_judges开始 共{}票 qualityFlags={}", allVotes.size(), qualityFlags);

        List<HorizonForecast> forecasts = new ArrayList<>(3);
        // 收集所有 horizon 的调权明细
        List<WeightAdjustment> allAdjustments = Collections.synchronizedList(new ArrayList<>());
        Map<String, Map<String, AgentStat>> finalStats = agentStats;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HorizonForecast>> futures = new ArrayList<>(3);
            // judge 实例需在主线程可见，用于取 adjustments
            HorizonJudge[] judges = new HorizonJudge[3];
            for (int i = 0; i < 3; i++) {
                judges[i] = new HorizonJudge(HORIZONS[i], finalStats, weightOverrideService);
                HorizonJudge judge = judges[i];
                futures.add(executor.submit(() -> judge.judge(allVotes, lastPrice, qualityFlags, regime)));
            }
            for (int i = 0; i < 3; i++) {
                try {
                    forecasts.add(futures.get(i).get(5, TimeUnit.SECONDS));
                    allAdjustments.addAll(judges[i].getWeightAdjustments());
                } catch (Exception e) {
                    log.warn("[Q4] Judge[{}]裁决失败: {}", HORIZONS[i], e.getMessage());
                    forecasts.add(HorizonForecast.noTrade(HORIZONS[i], 1.0));
                }
            }
        }

        for (HorizonForecast f : forecasts) {
            log.info("[Q4.result] {} → {} conf={} disagree={} score={} pos={}%",
                    f.horizon(), f.direction(),
                    String.format("%.2f", f.confidence()), String.format("%.2f", f.disagreement()),
                    String.format("%.3f", f.weightedScore()), String.format("%.1f", f.maxPositionPct() * 100));
        }

        String overallDecision = ConsensusBuilder.buildDecision(forecasts);
        String riskStatus = ConsensusBuilder.buildRiskStatus(forecasts);
        String cycleId = "qf-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF))
                + "-" + symbol;

        log.info("[Q4.end] run_judges完成 decision={} riskStatus={} cycleId={} adjustments={} 耗时{}ms",
                overallDecision, riskStatus, cycleId, allAdjustments.size(),
                System.currentTimeMillis() - startMs);

        Map<String, Object> result = new HashMap<>();
        result.put("horizon_forecasts", forecasts);
        result.put("overall_decision", overallDecision);
        result.put("risk_status", riskStatus);
        result.put("cycle_id", cycleId);
        // 调权摘要写入 state，GenerateReportNode 落到 snapshot_json
        if (!allAdjustments.isEmpty()) {
            result.put("memory_weight_adjustments", buildAdjustmentSummary(allAdjustments));
        }
        return result;
    }

    /** 构建可序列化的调权摘要列表，落入 snapshot_json */
    private List<Map<String, Object>> buildAdjustmentSummary(List<WeightAdjustment> adjustments) {
        List<Map<String, Object>> list = new ArrayList<>(adjustments.size());
        for (WeightAdjustment adj : adjustments) {
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
