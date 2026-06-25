package com.mawai.wiibquant.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.mawai.wiibquant.agent.quant.domain.AgentVote;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.MacroContext;
import com.mawai.wiibquant.agent.quant.factor.FactorAgent;
import com.mawai.wiibquant.agent.quant.factor.FactorEvaluationContext;
import com.mawai.wiibquant.agent.quant.factor.NewsEventAgent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Evidence Agents 并行执行节点：替代旧 run_factors。
 *
 * <p>与旧节点的关键区别：
 * <ul>
 *   <li>各 Agent 直接输出 H6/H12/H24 evidence，不再做 0_10/10_20/20_30 机械换标签</li>
 *   <li>输出 key 仍是 {@code agent_votes}（兼容持久化），但语义变为"evidence 辅助票"</li>
 * </ul>
 */
@Slf4j
public class RunEvidenceAgentsNode implements NodeAction {

    private static final List<String> HORIZONS = List.of("H6", "H12", "H24");

    private final List<FactorAgent> agents;

    public RunEvidenceAgentsNode(List<FactorAgent> agents) {
        this.agents = agents;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        FeatureSnapshot snapshot = (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        if (snapshot == null) {
            log.error("[Q4] feature_snapshot为空");
            return Map.of("agent_votes", List.of());
        }

        log.info("[Q4.0] run_evidence_agents开始 agents={} symbol={}", agents.size(), snapshot.symbol());
        List<AgentVote> allVotes = new ArrayList<>();
        List<NewsEventAgent.FilteredNewsItem> filteredNews = List.of();
        double newsConfidenceStddev = 0;
        boolean newsLowConfidence = false;
        MacroContext research = (MacroContext) state.value("research_forecast")
                .orElse(state.value("macro_context").orElse(null));
        FactorEvaluationContext agentContext = new FactorEvaluationContext(research);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<AgentVote>>> normalFutures = new ArrayList<>();
            List<String> normalAgentNames = new ArrayList<>();
            Future<NewsEventAgent.EvaluateResult> newsFuture = null;

            for (FactorAgent agent : agents) {
                if (agent instanceof NewsEventAgent newsAgent) {
                    newsFuture = executor.submit(() -> {
                        long start = System.currentTimeMillis();
                        NewsEventAgent.EvaluateResult result = newsAgent.evaluateWithNews(snapshot);
                        log.info("[Q4.agent] {}完成 {}ms {}票 {}条新闻",
                                newsAgent.name(), System.currentTimeMillis() - start,
                                result.votes().size(), result.filteredNews().size());
                        return result;
                    });
                } else {
                    normalAgentNames.add(agent.name());
                    normalFutures.add(executor.submit(() -> {
                        long start = System.currentTimeMillis();
                        List<AgentVote> votes = agent.evaluate(snapshot, agentContext);
                        log.info("[Q4.agent] {}完成 {}ms {}票",
                                agent.name(), System.currentTimeMillis() - start, votes.size());
                        return votes;
                    }));
                }
            }

            for (int i = 0; i < normalFutures.size(); i++) {
                String name = normalAgentNames.get(i);
                try {
                    List<AgentVote> raw = normalFutures.get(i).get(30, TimeUnit.SECONDS);
                    allVotes.addAll(raw);
                } catch (Exception e) {
                    log.warn("[Q4] Agent[{}] 超时/异常: {}", name, e.getMessage());
                    for (String h : HORIZONS) {
                        allVotes.add(AgentVote.noTrade(name, h, "TIMEOUT"));
                    }
                }
            }

            if (newsFuture != null) {
                try {
                    NewsEventAgent.EvaluateResult newsResult = newsFuture.get(200, TimeUnit.SECONDS);
                    allVotes.addAll(newsResult.votes());
                    filteredNews = newsResult.filteredNews();
                    newsConfidenceStddev = newsResult.confidenceStddev();
                    newsLowConfidence = newsResult.lowConfidence();
                } catch (Exception e) {
                    log.warn("[Q4] Agent[news_event] 超时/异常: {}", e.getMessage());
                    for (String h : HORIZONS) {
                        allVotes.add(AgentVote.noTrade("news_event", h, "TIMEOUT"));
                    }
                }
            }
        }

        FeatureSnapshot outputSnapshot = snapshot;
        if (allVotes.stream().anyMatch(v -> v.riskFlags().contains("LOW_CONFIDENCE"))
                || (research != null && !research.qualityFlags().isEmpty())) {
            List<String> flags = new ArrayList<>(snapshot.qualityFlags() != null ? snapshot.qualityFlags() : List.of());
            if (!flags.contains("LOW_CONFIDENCE")) {
                flags.add("LOW_CONFIDENCE");
            }
            if (research != null) {
                for (String flag : research.qualityFlags()) {
                    if (!flags.contains(flag)) {
                        flags.add(flag);
                    }
                }
            }
            outputSnapshot = snapshot.withRegimeReview(snapshot.regime(), List.copyOf(flags),
                    snapshot.regimeConfidence(), snapshot.regimeTransition());
        }

        log.info("[Q4.end] run_evidence_agents完成 共{}票 LLM筛选新闻={}条 耗时{}ms",
                allVotes.size(), filteredNews.size(), System.currentTimeMillis() - startMs);

        Map<String, Object> result = new HashMap<>();
        result.put("agent_votes", allVotes);
        result.put("filtered_news", filteredNews);
        result.put("feature_snapshot", outputSnapshot);
        result.put("news_confidence_stddev", newsConfidenceStddev);
        result.put("news_low_confidence", newsLowConfidence);
        return result;
    }
}
