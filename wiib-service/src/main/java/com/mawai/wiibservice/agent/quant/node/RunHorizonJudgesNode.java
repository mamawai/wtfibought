package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.mawai.wiibservice.agent.quant.domain.*;
import com.mawai.wiibservice.agent.quant.judge.ConsensusBuilder;
import com.mawai.wiibservice.agent.quant.judge.HorizonJudge;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 3个HorizonJudge并行裁决节点。
 * 内部虚拟线程并行执行，每个Judge只消费属于自己区间的投票。
 */
@Slf4j
public class RunHorizonJudgesNode implements NodeAction {

    private static final String[] HORIZONS = {"0_10", "10_20", "20_30"};
    private final MemoryService memoryService;

    public RunHorizonJudgesNode() { this(null); }
    public RunHorizonJudgesNode(MemoryService memoryService) { this.memoryService = memoryService; }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        List<AgentVote> allVotes = (List<AgentVote>) state.value("agent_votes").orElse(List.of());
        FeatureSnapshot snapshot = (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        BigDecimal lastPrice = snapshot != null ? snapshot.lastPrice() : null;
        List<String> qualityFlags = snapshot != null ? snapshot.qualityFlags() : List.of();
        String symbol = snapshot != null ? snapshot.symbol() : "BTCUSDT";

        // 查反思记忆中的agent准确率
        Map<String, Map<String, Double>> agentAccuracy = Map.of();
        if (memoryService != null) {
            try {
                agentAccuracy = memoryService.getAgentAccuracy(symbol);
                if (!agentAccuracy.isEmpty()) {
                    log.info("[Q4.0] 加载agent准确率 agents={}", agentAccuracy.keySet());
                }
            } catch (Exception e) {
                log.warn("[Q4.0] agent准确率查询失败: {}", e.getMessage());
            }
        }

        log.info("[Q4.0] run_judges开始 共{}票 qualityFlags={}", allVotes.size(), qualityFlags);

        List<HorizonForecast> forecasts = new ArrayList<>(3);
        Map<String, Map<String, Double>> finalAccuracy = agentAccuracy;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HorizonForecast>> futures = new ArrayList<>(3);
            for (String horizon : HORIZONS) {
                futures.add(executor.submit(() -> {
                    HorizonJudge judge = new HorizonJudge(horizon, finalAccuracy);
                    return judge.judge(allVotes, lastPrice, qualityFlags);
                }));
            }
            for (int i = 0; i < 3; i++) {
                try {
                    forecasts.add(futures.get(i).get(5, TimeUnit.SECONDS));
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
                + "-" + symbol;

        log.info("[Q4.end] run_judges完成 decision={} riskStatus={} cycleId={} 耗时{}ms",
                overallDecision, riskStatus, cycleId, System.currentTimeMillis() - startMs);

        Map<String, Object> result = new HashMap<>();
        result.put("horizon_forecasts", forecasts);
        result.put("overall_decision", overallDecision);
        result.put("risk_status", riskStatus);
        result.put("cycle_id", cycleId);
        return result;
    }
}
