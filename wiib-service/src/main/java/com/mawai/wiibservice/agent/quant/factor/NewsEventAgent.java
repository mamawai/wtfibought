package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibservice.agent.quant.domain.*;
import com.mawai.wiibservice.agent.quant.util.NewsRelevance;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 重周期新闻事件 Agent。
 * 先做 symbol 相关性过滤，再用 LLM 多次采样分析新闻影响，取 median 结果降低单次回答波动。
 */
@Slf4j
public class NewsEventAgent implements FactorAgent {

    private static final int NEWS_CALLS = 3;
    private static final double LOW_CONFIDENCE_STDDEV = 0.15;
    private static final List<String> HORIZONS = List.of("0_10", "10_20", "20_30");

    private final ChatClient chatClient;
    private final LlmCallMode callMode;

    public NewsEventAgent(ChatClient.Builder builder, LlmCallMode callMode) {
        this.chatClient = builder.build();
        this.callMode = callMode;
    }

    @Override
    public String name() { return "news_event"; }

    public record EvaluateResult(
            List<AgentVote> votes,
            List<FilteredNewsItem> filteredNews,
            double confidenceStddev,
            boolean lowConfidence
    ) {
        public EvaluateResult(List<AgentVote> votes, List<FilteredNewsItem> filteredNews) {
            this(votes, filteredNews, 0, false);
        }
    }

    public EvaluateResult evaluateWithNews(FeatureSnapshot snapshot) {
        List<NewsItem> allNewsItems = snapshot.newsItems();
        List<NewsItem> preFiltered = NewsRelevance.filterRelevant(snapshot.symbol(), allNewsItems);
        log.info("[Q3.news] symbol={} 总新闻={}条 关键词预过滤后={}条", snapshot.symbol(),
                allNewsItems != null ? allNewsItems.size() : 0, preFiltered.size());
        if (preFiltered.isEmpty()) {
            return new EvaluateResult(
                    List.of(AgentVote.noTrade(name(), "0_10", "NO_NEWS"),
                            AgentVote.noTrade(name(), "10_20", "NO_NEWS"),
                            AgentVote.noTrade(name(), "20_30", "NO_NEWS")),
                    List.of());
        }

        int baseVolBps = estimateVolBps(snapshot);
        try {
            String prompt = buildPrompt(snapshot.symbol(), preFiltered);
            List<EvaluateResult> samples = new ArrayList<>(NEWS_CALLS);
            for (int i = 1; i <= NEWS_CALLS; i++) {
                String response = callMode.call(chatClient, prompt);
                log.info("[Q3.news] LLM样本 {}/{} 返回 {}chars",
                        i, NEWS_CALLS, response != null ? response.length() : 0);
                samples.add(parseResponse(response, baseVolBps));
            }
            return medianResult(samples, baseVolBps);
        } catch (Exception e) {
            log.warn("[Q3.news] LLM调用失败: {}", e.getMessage());
            return new EvaluateResult(
                    List.of(AgentVote.noTrade(name(), "0_10", "LLM_ERROR"),
                            AgentVote.noTrade(name(), "10_20", "LLM_ERROR"),
                            AgentVote.noTrade(name(), "20_30", "LLM_ERROR")),
                    List.of());
        }
    }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot snapshot) {
        return evaluateWithNews(snapshot).votes();
    }

    private String buildPrompt(String symbol, List<NewsItem> newsItems) {
        StringBuilder newsText = new StringBuilder();
        for (int i = 0; i < newsItems.size(); i++) {
            NewsItem item = newsItems.get(i);
            newsText.append(i + 1).append(". ").append(item.title());
            if (item.summary() != null && !item.summary().isBlank()) {
                String summary = item.summary().length() > 300
                        ? item.summary().substring(0, 300) + "..." : item.summary();
                newsText.append("\n   ").append(summary);
            }
            newsText.append("\n");
        }
        return """
                你是加密货币新闻分析师。以下是与%s可能相关的新闻列表（已做初步关键词过滤）。

                【新闻列表】
                %s

                你需要完成两件事：

                【第一步：筛选】
                从上面的新闻中，挑出真正会对%s短期价格产生影响的新闻（最多5条）。
                排除标准：
                - 与%s无直接关系的其他币种新闻
                - 纯技术分析/预测类文章（不是事件）
                - 老生常谈、没有新信息量的新闻
                - 广告、软文、项目推广

                【第二步：分析】
                对筛选出的新闻，综合判断整体情绪和对三个时间区间的影响。
                - 新闻对超短线(0-10min)影响最小，对短线(10-30min)影响较大
                - 重大事件（监管、黑客、ETF、宏观政策）影响更大
                - 普通行情新闻影响很小，score接近0

                严格返回以下JSON（不要markdown包裹）：
                {
                  "filteredNews": [
                    {"index":1,"title":"标题","sentiment":"bullish/bearish/neutral","impact":"high/medium/low","reason":"一句话说明影响逻辑"}
                  ],
                  "votes": [
                    {"horizon":"0_10","score":0.0,"confidence":0.0,"reasonCodes":[],"riskFlags":[]},
                    {"horizon":"10_20","score":0.0,"confidence":0.0,"reasonCodes":[],"riskFlags":[]},
                    {"horizon":"20_30","score":0.0,"confidence":0.0,"reasonCodes":[],"riskFlags":[]}
                  ]
                }

                字段说明：
                - score: [-1,1]，正=利多，负=利空，0=中性
                - confidence: [0,1]，新闻质量和相关性越高越大
                - reasonCodes: 如 NEWS_BULLISH_ETF, NEWS_BEARISH_REGULATION, NEWS_NEUTRAL
                - riskFlags: 如 BLACK_SWAN_RISK, REGULATORY_UNCERTAINTY
                - 如果筛选后没有一条有影响力的新闻，filteredNews为空数组，votes的score全部填0
                """.formatted(symbol, newsText.toString(), symbol, symbol);
    }

    @SuppressWarnings("unchecked")
    private EvaluateResult parseResponse(String response, int baseVolBps) {
        if (response == null || response.isBlank()) {
            return new EvaluateResult(defaultVotes("PARSE_ERROR"), List.of());
        }

        try {
            String json = JsonUtils.extractJson(response);
            Map<String, Object> root = JSON.parseObject(json, Map.class);

            List<FilteredNewsItem> filteredNews = List.of();
            List<Map<String, Object>> filtered = (List<Map<String, Object>>) root.get("filteredNews");
            if (filtered != null && !filtered.isEmpty()) {
                List<FilteredNewsItem> items = new ArrayList<>(filtered.size());
                for (Map<String, Object> item : filtered) {
                    items.add(new FilteredNewsItem(
                            String.valueOf(item.getOrDefault("title", "")),
                            String.valueOf(item.getOrDefault("sentiment", "neutral")),
                            String.valueOf(item.getOrDefault("impact", "low")),
                            String.valueOf(item.getOrDefault("reason", ""))
                    ));
                }
                filteredNews = List.copyOf(items);
                log.info("[Q3.news] LLM筛选出{}条有效新闻: {}", items.size(),
                        items.stream().map(i -> i.sentiment() + ":" + i.title()).toList());
            }

            List<Map<String, Object>> votes = (List<Map<String, Object>>) root.get("votes");
            if (votes == null || votes.size() < 3) {
                return new EvaluateResult(defaultVotes("INVALID_FORMAT"), filteredNews);
            }

            List<AgentVote> result = new ArrayList<>(3);
            for (int i = 0; i < 3 && i < votes.size(); i++) {
                Map<String, Object> v = votes.get(i);
                String horizon = (String) v.get("horizon");
                double score = clamp(toDouble(v.get("score")));
                double conf = Math.clamp(toDouble(v.get("confidence")), 0, 1);

                // 新闻时效衰减：远期窗口 confidence 递减（新闻效应短期最强）
                if ("10_20".equals(horizon)) conf *= 0.85;
                else if ("20_30".equals(horizon)) conf *= 0.7;

                List<String> reasons = v.get("reasonCodes") instanceof List<?> l
                        ? l.stream().map(String::valueOf).toList() : List.of();
                List<String> flags = v.get("riskFlags") instanceof List<?> l
                        ? l.stream().map(String::valueOf).toList() : List.of();

                Direction dir = Math.abs(score) < 0.05 ? Direction.NO_TRADE
                        : (score > 0 ? Direction.LONG : Direction.SHORT);
                int moveBps = (int) (Math.abs(score) * baseVolBps * 0.5);

                result.add(new AgentVote(name(), horizon, dir, score, conf,
                        moveBps, baseVolBps, reasons, flags));
            }
            return new EvaluateResult(result, filteredNews);
        } catch (Exception e) {
            log.warn("[Q3.news] 解析失败: {}", e.getMessage());
            return new EvaluateResult(defaultVotes("PARSE_ERROR"), List.of());
        }
    }

    private EvaluateResult medianResult(List<EvaluateResult> samples, int baseVolBps) {
        List<AgentVote> merged = new ArrayList<>(3);
        double maxStddev = 0;
        boolean lowConfidence = false;

        for (String horizon : HORIZONS) {
            List<AgentVote> votes = samples.stream()
                    .map(sample -> findVote(sample.votes(), horizon))
                    .toList();
            double medianScore = median(votes.stream().mapToDouble(AgentVote::score).sorted().toArray());
            double medianConf = median(votes.stream().mapToDouble(AgentVote::confidence).sorted().toArray());
            double scoreStddev = stddev(votes.stream().mapToDouble(AgentVote::score).toArray());
            double confStddev = stddev(votes.stream().mapToDouble(AgentVote::confidence).toArray());
            double horizonStddev = Math.max(scoreStddev, confStddev);
            maxStddev = Math.max(maxStddev, horizonStddev);
            if (horizonStddev > LOW_CONFIDENCE_STDDEV) {
                lowConfidence = true;
            }

            AgentVote source = nearestVote(votes, medianScore, medianConf);
            List<String> flags = new ArrayList<>(source.riskFlags());
            if (horizonStddev > LOW_CONFIDENCE_STDDEV && !flags.contains("LOW_CONFIDENCE")) {
                flags.add("LOW_CONFIDENCE");
            }
            Direction dir = Math.abs(medianScore) < 0.05 ? Direction.NO_TRADE
                    : (medianScore > 0 ? Direction.LONG : Direction.SHORT);
            int moveBps = (int) (Math.abs(medianScore) * baseVolBps * 0.5);
            merged.add(new AgentVote(name(), horizon, dir, medianScore, medianConf,
                    moveBps, baseVolBps, source.reasonCodes(), List.copyOf(flags)));
        }

        EvaluateResult newsSource = medianNewsSource(samples);
        log.info("[Q3.news] 3次median完成 maxStddev={} lowConfidence={}",
                String.format("%.3f", maxStddev), lowConfidence);
        return new EvaluateResult(merged, newsSource.filteredNews(), maxStddev, lowConfidence);
    }

    private AgentVote findVote(List<AgentVote> votes, String horizon) {
        return votes.stream()
                .filter(v -> horizon.equals(v.horizon()))
                .findFirst()
                .orElse(AgentVote.noTrade(name(), horizon, "MISSING_LLM_VOTE"));
    }

    private AgentVote nearestVote(List<AgentVote> votes, double score, double confidence) {
        AgentVote selected = votes.getFirst();
        double bestDistance = Math.abs(selected.score() - score) + Math.abs(selected.confidence() - confidence);
        for (AgentVote vote : votes) {
            double distance = Math.abs(vote.score() - score) + Math.abs(vote.confidence() - confidence);
            if (distance < bestDistance) {
                selected = vote;
                bestDistance = distance;
            }
        }
        return selected;
    }

    private EvaluateResult medianNewsSource(List<EvaluateResult> samples) {
        double[] avgConfidence = samples.stream()
                .mapToDouble(sample -> sample.votes().stream().mapToDouble(AgentVote::confidence).average().orElse(0))
                .sorted()
                .toArray();
        double median = median(avgConfidence);
        EvaluateResult selected = samples.getFirst();
        double bestDistance = Math.abs(selected.votes().stream().mapToDouble(AgentVote::confidence).average().orElse(0) - median);
        for (EvaluateResult sample : samples) {
            double distance = Math.abs(sample.votes().stream().mapToDouble(AgentVote::confidence).average().orElse(0) - median);
            if (distance < bestDistance) {
                selected = sample;
                bestDistance = distance;
            }
        }
        return selected;
    }

    private static double median(double[] sortedValues) {
        if (sortedValues.length == 0) {
            return 0;
        }
        return sortedValues[sortedValues.length / 2];
    }

    private static double stddev(double[] values) {
        if (values.length < 2) {
            return 0;
        }
        double avg = 0;
        for (double value : values) {
            avg += value;
        }
        avg /= values.length;
        double variance = 0;
        for (double value : values) {
            variance += Math.pow(value - avg, 2);
        }
        return Math.sqrt(variance / values.length);
    }

    private List<AgentVote> defaultVotes(String reason) {
        return List.of(
                AgentVote.noTrade(name(), "0_10", reason),
                AgentVote.noTrade(name(), "10_20", reason),
                AgentVote.noTrade(name(), "20_30", reason));
    }

    private static double clamp(double v) { return Math.clamp(v, -1, 1); }

    private static int estimateVolBps(FeatureSnapshot snapshot) {
        java.math.BigDecimal lastPrice = snapshot.lastPrice();
        java.math.BigDecimal atr = snapshot.atr5m() != null ? snapshot.atr5m() : snapshot.atr1m();
        if (atr != null && lastPrice != null && lastPrice.signum() > 0) {
            return atr.multiply(java.math.BigDecimal.valueOf(10000))
                    .divide(lastPrice, 0, java.math.RoundingMode.HALF_UP).intValue();
        }
        return 30;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0;
    }

    public record FilteredNewsItem(String title, String sentiment, String impact, String reason) {}
}
