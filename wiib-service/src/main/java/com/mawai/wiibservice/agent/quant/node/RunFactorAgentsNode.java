package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibservice.agent.quant.factor.FactorAgent;
import com.mawai.wiibservice.agent.quant.factor.NewsEventAgent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 6个因子Agent并行执行节点。
 * 内部使用虚拟线程并行，纯Java Agent超时30s，LLM Agent超时60-200s。
 */
@Slf4j
public class RunFactorAgentsNode implements NodeAction {

    private final List<FactorAgent> agents;

    public RunFactorAgentsNode(List<FactorAgent> agents) {
        this.agents = agents;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        FeatureSnapshot snapshot = (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        if (snapshot == null) {
            log.error("[Q3] feature_snapshot为空");
            return Map.of("agent_votes", List.of());
        }

        log.info("[Q3.0] run_factors开始 agents={} symbol={}", agents.size(), snapshot.symbol());
        List<AgentVote> allVotes = new ArrayList<>();
        List<NewsEventAgent.FilteredNewsItem> filteredNews = List.of();
        double newsConfidenceStddev = 0;
        boolean newsLowConfidence = false;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<AgentVote>>> normalFutures = new ArrayList<>();
            List<String> normalAgentNames = new ArrayList<>();
            Future<NewsEventAgent.EvaluateResult> newsFuture = null;

            for (FactorAgent agent : agents) {
                if (agent instanceof NewsEventAgent newsAgent) {
                    newsFuture = executor.submit(() -> {
                        long start = System.currentTimeMillis();
                        NewsEventAgent.EvaluateResult result = newsAgent.evaluateWithNews(snapshot);
                        log.info("[Q3.agent] {}完成 {}ms {}票 {}条新闻",
                                newsAgent.name(), System.currentTimeMillis() - start,
                                result.votes().size(), result.filteredNews().size());
                        return result;
                    });
                } else {
                    normalAgentNames.add(agent.name());
                    normalFutures.add(executor.submit(() -> {
                        long start = System.currentTimeMillis();
                        List<AgentVote> votes = agent.evaluate(snapshot);
                        log.info("[Q3.agent] {}完成 {}ms {}票",
                                agent.name(), System.currentTimeMillis() - start, votes.size());
                        return votes;
                    }));
                }
            }

            for (int i = 0; i < normalFutures.size(); i++) {
                String name = normalAgentNames.get(i);
                try {
                    allVotes.addAll(normalFutures.get(i).get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    log.warn("[Q3] Agent[{}] 超时/异常: {}", name, e.getMessage());
                    allVotes.add(AgentVote.noTrade(name, "0_10", "TIMEOUT"));
                    allVotes.add(AgentVote.noTrade(name, "10_20", "TIMEOUT"));
                    allVotes.add(AgentVote.noTrade(name, "20_30", "TIMEOUT"));
                }
            }

            // 收集 NewsEventAgent 结果
            if (newsFuture != null) {
                try {
                    NewsEventAgent.EvaluateResult newsResult = newsFuture.get(200, TimeUnit.SECONDS);
                    allVotes.addAll(newsResult.votes());
                    filteredNews = newsResult.filteredNews();
                    newsConfidenceStddev = newsResult.confidenceStddev();
                    newsLowConfidence = newsResult.lowConfidence();
                } catch (Exception e) {
                    log.warn("[Q3] Agent[news_event] 超时/异常: {}", e.getMessage());
                    allVotes.add(AgentVote.noTrade("news_event", "0_10", "TIMEOUT"));
                    allVotes.add(AgentVote.noTrade("news_event", "10_20", "TIMEOUT"));
                    allVotes.add(AgentVote.noTrade("news_event", "20_30", "TIMEOUT"));
                }
            }
        }

        FeatureSnapshot outputSnapshot = snapshot;
        if (allVotes.stream().anyMatch(v -> v.riskFlags().contains("LOW_CONFIDENCE"))) {
            List<String> flags = new ArrayList<>(snapshot.qualityFlags() != null ? snapshot.qualityFlags() : List.of());
            if (!flags.contains("LOW_CONFIDENCE")) {
                flags.add("LOW_CONFIDENCE");
            }
            outputSnapshot = snapshot.withRegimeReview(snapshot.regime(), List.copyOf(flags),
                    snapshot.regimeConfidence(), snapshot.regimeTransition());
        }

        log.info("[Q3.end] run_factors完成 共{}票 LLM筛选新闻={}条 耗时{}ms",
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
