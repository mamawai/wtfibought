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

@Slf4j
public class NewsEventAgent implements FactorAgent {

    private final ChatClient chatClient;
    private final LlmCallMode callMode;
    private List<FilteredNewsItem> lastFilteredNews = List.of();

    public NewsEventAgent(ChatClient.Builder builder, LlmCallMode callMode) {
        this.chatClient = builder.build();
        this.callMode = callMode;
    }

    @Override
    public String name() { return "news_event"; }

    public List<FilteredNewsItem> getLastFilteredNews() { return lastFilteredNews; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot snapshot) {
        lastFilteredNews = List.of();
        List<NewsItem> allNewsItems = snapshot.newsItems();
        List<NewsItem> preFiltered = NewsRelevance.filterRelevant(snapshot.symbol(), allNewsItems);
        log.info("[Q3.news] symbol={} 总新闻={}条 关键词预过滤后={}条", snapshot.symbol(),
                allNewsItems != null ? allNewsItems.size() : 0, preFiltered.size());
        if (preFiltered.isEmpty()) {
            return List.of(
                    AgentVote.noTrade(name(), "0_10", "NO_NEWS"),
                    AgentVote.noTrade(name(), "10_20", "NO_NEWS"),
                    AgentVote.noTrade(name(), "20_30", "NO_NEWS"));
        }

        int baseVolBps = estimateVolBps(snapshot);

        try {
            String prompt = buildPrompt(snapshot.symbol(), preFiltered);
            String response = callMode.call(chatClient, prompt);
            log.info("[Q3.news] LLM返回 {}chars", response != null ? response.length() : 0);
            return parseResponse(response, baseVolBps);
        } catch (Exception e) {
            log.warn("[Q3.news] LLM调用失败: {}", e.getMessage());
            return List.of(
                    AgentVote.noTrade(name(), "0_10", "LLM_ERROR"),
                    AgentVote.noTrade(name(), "10_20", "LLM_ERROR"),
                    AgentVote.noTrade(name(), "20_30", "LLM_ERROR"));
        }
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
    private List<AgentVote> parseResponse(String response, int baseVolBps) {
        if (response == null || response.isBlank()) {
            return defaultVotes("PARSE_ERROR");
        }

        try {
            String json = JsonUtils.extractJson(response);
            Map<String, Object> root = JSON.parseObject(json, Map.class);

            // 解析筛选结果
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
                lastFilteredNews = List.copyOf(items);
                log.info("[Q3.news] LLM筛选出{}条有效新闻: {}", items.size(),
                        items.stream().map(i -> i.sentiment() + ":" + i.title()).toList());
            }

            // 解析投票
            List<Map<String, Object>> votes = (List<Map<String, Object>>) root.get("votes");
            if (votes == null || votes.size() != 3) {
                return defaultVotes("INVALID_FORMAT");
            }

            List<AgentVote> result = new ArrayList<>(3);
            for (Map<String, Object> v : votes) {
                String horizon = (String) v.get("horizon");
                double score = clamp(toDouble(v.get("score")));
                double conf = Math.max(0, Math.min(1, toDouble(v.get("confidence"))));
                List<String> reasons = v.get("reasonCodes") instanceof List<?> l
                        ? l.stream().map(String::valueOf).toList() : List.of();
                List<String> flags = v.get("riskFlags") instanceof List<?> l
                        ? l.stream().map(String::valueOf).toList() : List.of();

                Direction dir = Math.abs(score) < 0.05 ? Direction.NO_TRADE
                        : (score > 0 ? Direction.LONG : Direction.SHORT);
                int volBps = baseVolBps;
                int moveBps = (int) (Math.abs(score) * volBps * 0.5);

                result.add(new AgentVote(name(), horizon, dir, score, conf,
                        moveBps, volBps, reasons, flags));
            }
            return result;
        } catch (Exception e) {
            log.warn("[Q3.news] 解析失败: {}", e.getMessage());
            return defaultVotes("PARSE_ERROR");
        }
    }

    private List<AgentVote> defaultVotes(String reason) {
        return List.of(
                AgentVote.noTrade(name(), "0_10", reason),
                AgentVote.noTrade(name(), "10_20", reason),
                AgentVote.noTrade(name(), "20_30", reason));
    }

    private static double clamp(double v) { return Math.max(-1, Math.min(1, v)); }

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
