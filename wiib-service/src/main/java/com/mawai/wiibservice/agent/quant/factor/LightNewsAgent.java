package com.mawai.wiibservice.agent.quant.factor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.Direction;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibservice.agent.quant.domain.LlmCallMode;
import com.mawai.wiibservice.agent.quant.domain.NewsItem;
import com.mawai.wiibservice.agent.quant.util.NewsRelevance;
import com.mawai.wiibservice.config.BinanceRestClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 轻周期新闻刷新 Agent（浅LLM）。
 * 流程：拉最新10条 → publishedOn > heavyCycleEpoch 过滤增量 → symbol 相关性过滤 → LLM 打分。
 * 仅在 dMin 能映射回父重周期的窗口才被调用，按 activeHorizons 裁剪输出维度（减少 prompt）。
 * 无状态：ChatClient.Builder 每次外部传入（配合 AiAgentRuntimeManager 动态切模型）。
 */
@Slf4j
public class LightNewsAgent {

    private static final String AGENT_NAME = "news_event";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    public record Result(List<AgentVote> votes, boolean useCache, String status) {
        public static Result useCache(String status) {
            return new Result(List.of(), true, status);
        }
    }

    public Result evaluate(String symbol,
                           FeatureSnapshot snapshot,
                           long heavyCycleEpochSec,
                           List<String> activeHorizons,
                           ChatClient.Builder shallowChatBuilder,
                           LlmCallMode callMode,
                           BinanceRestClient binanceRestClient) {
        if (activeHorizons == null || activeHorizons.isEmpty()) {
            return Result.useCache("NO_ACTIVE_HORIZON");
        }
        String coin = symbol.replace("USDT", "").replace("USDC", "");
        String newsJson;
        try {
            newsJson = binanceRestClient.getCryptoNews(coin, 10, "EN");
        } catch (Exception e) {
            log.warn("[LightNews] news API 异常 symbol={}: {}", symbol, e.getMessage());
            return Result.useCache("NEWS_API_ERROR");
        }
        if (newsJson == null || newsJson.isBlank() || "{}".equals(newsJson)) {
            return Result.useCache("NO_NEWS_API");
        }

        List<NewsItem> all = parseNews(newsJson);
        if (all.isEmpty()) {
            return Result.useCache("NO_NEWS_PARSED");
        }

        // 时间过滤：仅保留 publishedOn > heavyCycleEpoch（重周期之后新到的）
        List<NewsItem> delta = new ArrayList<>();
        for (NewsItem n : all) {
            if (n.publishedOn() > heavyCycleEpochSec) delta.add(n);
        }
        if (delta.isEmpty()) {
            log.info("[LightNews] symbol={} 无增量新闻 heavyTs={}", symbol, heavyCycleEpochSec);
            return Result.useCache("NO_DELTA");
        }

        // symbol 相关性二次过滤（复用重周期 util）
        List<NewsItem> relevant = NewsRelevance.filterRelevant(symbol, delta);
        if (relevant.isEmpty()) {
            return Result.useCache("NO_RELEVANT");
        }

        log.info("[LightNews] symbol={} 增量={}条 相关={}条 horizons={}",
                symbol, delta.size(), relevant.size(), activeHorizons);

        String prompt = buildPrompt(symbol, snapshot, relevant, activeHorizons, heavyCycleEpochSec);
        String response;
        try {
            ChatClient chatClient = shallowChatBuilder.build();
            response = callMode.call(chatClient, prompt);
        } catch (Exception e) {
            log.warn("[LightNews] LLM 调用失败 symbol={}: {}", symbol, e.getMessage());
            return Result.useCache("LLM_ERROR");
        }
        if (response == null || response.isBlank()) {
            return Result.useCache("LLM_EMPTY");
        }

        List<AgentVote> votes = parseVotes(response, snapshot, activeHorizons);
        if (votes.isEmpty()) {
            return Result.useCache("LLM_PARSE_ERROR");
        }
        log.info("[LightNews] symbol={} LLM 产出 {} 条 votes", symbol, votes.size());
        return new Result(votes, false, "LLM_OK");
    }

    // byte 模式解析，绕开 fastjson2 char buffer 扩容 bug（与 BuildFeaturesNode 保持一致）
    private static List<NewsItem> parseNews(String newsJson) {
        try {
            JSONObject root = JSON.parseObject(newsJson.getBytes(StandardCharsets.UTF_8));
            JSONArray items = root.getJSONArray("Data");
            if (items == null || items.isEmpty()) return List.of();
            List<NewsItem> result = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                JSONObject it = items.getJSONObject(i);
                if (it == null) continue;
                result.add(new NewsItem(
                        it.getString("TITLE"),
                        it.getString("BODY"),
                        it.getString("SOURCE_DATA_SOURCE_KEY"),
                        it.getString("GUID"),
                        it.getLongValue("PUBLISHED_ON")
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("[LightNews] news 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static String buildPrompt(String symbol, FeatureSnapshot snapshot,
                                      List<NewsItem> items, List<String> activeHorizons,
                                      long heavyCycleEpochSec) {
        StringBuilder newsText = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            NewsItem n = items.get(i);
            String t = TIME_FMT.format(Instant.ofEpochSecond(n.publishedOn()));
            String summary = n.summary() == null ? "" : n.summary();
            if (summary.length() > 150) summary = summary.substring(0, 150) + "...";
            newsText.append(i + 1).append(". [").append(t).append("] ")
                    .append(n.title() == null ? "" : n.title());
            if (!summary.isBlank()) newsText.append(" | ").append(summary);
            newsText.append("\n");
        }

        StringBuilder votesTemplate = new StringBuilder();
        for (int i = 0; i < activeHorizons.size(); i++) {
            if (i > 0) votesTemplate.append(",\n    ");
            votesTemplate.append("{\"horizon\":\"").append(activeHorizons.get(i))
                    .append("\",\"score\":0.0,\"confidence\":0.0,\"reasonCodes\":[],\"riskFlags\":[]}");
        }

        long dMin = (Instant.now().getEpochSecond() - heavyCycleEpochSec) / 60;
        String atrStr = snapshot.atr5m() != null ? snapshot.atr5m().toPlainString() : "N/A";
        String priceStr = snapshot.lastPrice() != null ? snapshot.lastPrice().toPlainString() : "N/A";

        return """
                你是加密货币实时新闻分析师（轻周期增量分析）。

                【市场快照】
                symbol: %s  当前价: %s  ATR5m: %s
                regime: %s  距上次深度分析: %dmin

                【增量新闻 (%d 条)】
                %s
                【任务】
                对以下时间区间综合打分（严格 JSON，无 markdown）。
                如新闻与 %s 无直接或宏观关联，score/confidence 填 0。

                需要产出的 horizons: %s

                {
                  "votes": [
                    %s
                  ]
                }

                字段说明：
                - score ∈ [-1,1]：+利多 / -利空
                - confidence ∈ [0,1]：新闻质量×相关性（本轮仅 %d 条增量，整体置信度放低）
                - reasonCodes：如 NEWS_BULLISH_ETF / NEWS_BEARISH_REGULATION / NEWS_NEUTRAL
                - riskFlags：如 BLACK_SWAN_RISK / REGULATORY_UNCERTAINTY
                """.formatted(
                        symbol, priceStr, atrStr,
                        snapshot.regime(), dMin,
                        items.size(), newsText,
                        symbol, activeHorizons,
                        votesTemplate, items.size());
    }

    @SuppressWarnings("unchecked")
    private static List<AgentVote> parseVotes(String response, FeatureSnapshot snapshot,
                                              List<String> activeHorizons) {
        try {
            String json = JsonUtils.extractJson(response);
            Map<String, Object> root = JSON.parseObject(json, Map.class);
            List<Map<String, Object>> votes = (List<Map<String, Object>>) root.get("votes");
            if (votes == null || votes.isEmpty()) return List.of();

            int baseVolBps = estimateVolBps(snapshot);
            List<AgentVote> result = new ArrayList<>(activeHorizons.size());
            for (Map<String, Object> v : votes) {
                String horizon = String.valueOf(v.get("horizon"));
                if (!activeHorizons.contains(horizon)) continue;
                double score = Math.clamp(toDouble(v.get("score")), -1, 1);
                double conf = Math.clamp(toDouble(v.get("confidence")), 0, 1);
                // 时效衰减：与 NewsEventAgent 一致（新闻效应短期最强）
                if ("10_20".equals(horizon)) conf *= 0.85;
                else if ("20_30".equals(horizon)) conf *= 0.70;

                List<String> reasons = v.get("reasonCodes") instanceof List<?> l
                        ? l.stream().map(String::valueOf).toList() : List.of();
                List<String> flags = v.get("riskFlags") instanceof List<?> l
                        ? l.stream().map(String::valueOf).toList() : List.of();

                Direction dir = Math.abs(score) < 0.05 ? Direction.NO_TRADE
                        : (score > 0 ? Direction.LONG : Direction.SHORT);
                int moveBps = (int) (Math.abs(score) * baseVolBps * 0.5);
                result.add(new AgentVote(AGENT_NAME, horizon, dir, score, conf,
                        moveBps, baseVolBps, reasons, flags));
            }
            return result;
        } catch (Exception e) {
            log.warn("[LightNews] 解析响应失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static int estimateVolBps(FeatureSnapshot snapshot) {
        BigDecimal lastPrice = snapshot.lastPrice();
        BigDecimal atr = snapshot.atr5m() != null ? snapshot.atr5m() : snapshot.atr1m();
        if (atr != null && lastPrice != null && lastPrice.signum() > 0) {
            return atr.multiply(BigDecimal.valueOf(10000))
                    .divide(lastPrice, 0, RoundingMode.HALF_UP).intValue();
        }
        return 30;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return 0;
    }
}
