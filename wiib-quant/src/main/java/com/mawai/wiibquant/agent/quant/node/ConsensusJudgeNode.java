package com.mawai.wiibquant.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.mawai.wiibquant.agent.quant.domain.AgentVote;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.HorizonForecast;
import com.mawai.wiibquant.agent.quant.domain.MacroContext;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityScore;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;
import com.mawai.wiibquant.agent.quant.judge.ConsensusForecast;
import com.mawai.wiibquant.agent.quant.judge.ConsensusJudge;
import com.mawai.wiibquant.agent.quant.judge.FragilityScorer;
import com.mawai.wiibquant.agent.quant.judge.HorizonDecisionPolicy;
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
 * 共识裁决节点：对 H6/H12/H24 三区间并行裁决。
 *
 * <p>读 research_forecast（MacroContext）作为主票锚定方向，evidence votes 作为<b>静态加权</b>辅助修正，
 * 输出 ConsensusForecast（只有方向/置信度/分歧）。按方向命中率调权的死回路已移除。</p>
 */
@Slf4j
public class ConsensusJudgeNode implements NodeAction {

    private static final String[] HORIZONS = {"H6", "H12", "H24"};

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();

        // 读 research forecast 主票
        MacroContext researchForecast = (MacroContext) state.value("research_forecast")
                .orElse(state.value("macro_context").orElse(null));

        // 读 evidence votes（旧 key: agent_votes，过渡期兼容）
        List<AgentVote> evidenceVotes = (List<AgentVote>) state.value("agent_votes").orElse(List.of());

        String symbol = (String) state.value("target_symbol").orElse("BTCUSDT");

        log.info("[Q5.0] consensus_judge开始 evidenceVotes={}", evidenceVotes.size());

        // 脆弱度合成（确定性，简报头条）：读 snapshot + 信号面板，与方向裁决并列产出，互不依赖
        FeatureSnapshot snapshot = (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        SignalPanel signalPanel = (SignalPanel) state.value("signal_panel").orElse(SignalPanel.empty());
        FragilityScore fragility = new FragilityScorer().score(snapshot, signalPanel);
        log.info("[Q5.fragility] {} score={} level={} crowd={} delev={} vol={} dir={}",
                symbol, fragility.score(), fragility.level(),
                String.format("%.2f", fragility.crowding()),
                String.format("%.2f", fragility.deleveraging()),
                String.format("%.2f", fragility.volState()), fragility.direction());

        List<ConsensusForecast> forecasts = new ArrayList<>(3);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ConsensusForecast>> futures = new ArrayList<>(3);

            for (String horizonKey : HORIZONS) {
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

                ConsensusJudge judge = new ConsensusJudge(horizonKey, leg.directionSign(),
                        leg.directionConfidence());
                futures.add(executor.submit(() -> judge.judge(horizonVotes)));
            }

            for (int i = 0; i < HORIZONS.length; i++) {
                try {
                    forecasts.add(futures.get(i).get(5, TimeUnit.SECONDS));
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

        log.info("[Q5.end] consensus_judge完成 decision={} riskStatus={} cycleId={} 耗时{}ms",
                overallDecision, riskStatus, cycleId, System.currentTimeMillis() - startMs);

        Map<String, Object> result = new HashMap<>();
        result.put("consensus_forecasts", forecasts);
        result.put("fragility_score", fragility);

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
        return result;
    }
}
