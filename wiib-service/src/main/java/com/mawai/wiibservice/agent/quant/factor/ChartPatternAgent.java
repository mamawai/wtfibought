package com.mawai.wiibservice.agent.quant.factor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibservice.agent.quant.chart.ChartImageGenerator;
import com.mawai.wiibservice.agent.quant.domain.*;
import com.mawai.wiibservice.config.BinanceRestClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * K线图表形态识别Agent — 第6个FactorAgent。
 * 生成1h和4h蜡烛图，发给多模态LLM识别图表形态（头肩、双底、三角形等）。
 * 输出AgentVote参与信号层投票。
 */
@Slf4j
public class ChartPatternAgent implements FactorAgent {

    private final ChatClient chatClient;
    private final BinanceRestClient binanceRestClient;
    private final LlmCallMode callMode;

    public ChartPatternAgent(ChatClient.Builder builder, BinanceRestClient binanceRestClient, LlmCallMode callMode) {
        this.chatClient = builder.build();
        this.binanceRestClient = binanceRestClient;
        this.callMode = callMode;
    }

    @Override
    public String name() { return "chart_pattern"; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot snapshot) {
        String symbol = snapshot.symbol();
        int baseVolBps = estimateVolBps(snapshot);

        try {
            // 生成1h和4h K线图
            byte[] chart1h = generateChart(symbol, "1h", 168);
            byte[] chart4h = generateChart(symbol, "4h", 120);

            if (chart1h == null && chart4h == null) {
                log.warn("[Q3.chart] K线图生成失败 symbol={}", symbol);
                return defaultVotes("CHART_GEN_FAILED");
            }

            // 发送给多模态LLM
            String prompt = buildPrompt(symbol);
            String response = callWithImages(prompt, chart1h, chart4h);
            log.info("[Q3.chart] LLM返回 {}chars", response != null ? response.length() : 0);

            return parseResponse(response, baseVolBps);
        } catch (Exception e) {
            log.warn("[Q3.chart] 形态识别失败: {}", e.getMessage());
            return defaultVotes("LLM_ERROR");
        }
    }

    private byte[] generateChart(String symbol, String interval, int limit) {
        try {
            String klineJson = binanceRestClient.getFuturesKlines(symbol, interval, limit, null);
            return ChartImageGenerator.generate(klineJson, symbol, interval);
        } catch (Exception e) {
            log.warn("[Q3.chart] {}K线获取失败: {}", interval, e.getMessage());
            return null;
        }
    }

    private String buildPrompt(String symbol) {
        return """
                你是专业的加密货币图表技术分析师。请分析以下%s的K线图（可能包含1h和4h两张图）。

                【分析要求】
                1. 识别当前图表中存在的技术形态（只报告你有较高把握的，不要猜测）：
                   - 反转形态：头肩顶/底、双顶/底、V型反转
                   - 持续形态：上升/下降三角形、旗形、楔形、矩形
                   - 通道：上升/下降通道、水平通道
                2. 判断当前价格在形态中的位置（初期/中期/末期/已突破）
                3. 观察均线排列状态（EMA5/20/60的多头/空头排列、交叉）
                4. 综合给出方向偏向

                【输出规则】
                - 如果没有识别到明确形态，score应接近0
                - 图表形态对短线(0-10min)影响很小，对中线(20-30min)影响较大
                - confidence反映你对形态识别的把握度，不确定就给低值

                严格返回JSON（不要markdown包裹）：
                {
                  "patterns": [
                    {"name": "ascending_triangle", "direction": "bullish", "confidence": 0.7, "stage": "near_breakout"}
                  ],
                  "emaStatus": "EMA5>EMA20>EMA60多头排列",
                  "overallBias": "bullish",
                  "votes": [
                    {"horizon": "0_10", "score": 0.1, "confidence": 0.2, "reasonCodes": ["ASCENDING_TRIANGLE"]},
                    {"horizon": "10_20", "score": 0.3, "confidence": 0.4, "reasonCodes": ["ASCENDING_TRIANGLE","EMA_BULLISH"]},
                    {"horizon": "20_30", "score": 0.4, "confidence": 0.5, "reasonCodes": ["ASCENDING_TRIANGLE","EMA_BULLISH"]}
                  ]
                }

                字段说明：
                - patterns: 识别到的形态列表，没有就空数组
                - score: [-1,1]，正=偏多，负=偏空，0=无方向
                - confidence: [0,1]，形态越清晰越高
                - reasonCodes: 如 HEAD_AND_SHOULDERS, DOUBLE_BOTTOM, ASCENDING_TRIANGLE, EMA_BULLISH, EMA_BEARISH, CHANNEL_UP, CHANNEL_DOWN, NO_PATTERN
                """.formatted(symbol);
    }

    private String callWithImages(String prompt, byte[] chart1h, byte[] chart4h) {
        List<org.springframework.ai.content.Media> mediaList = new ArrayList<>();
        MimeType pngType = MimeType.valueOf("image/png");

        if (chart1h != null) {
            mediaList.add(new org.springframework.ai.content.Media(pngType, new ByteArrayResource(chart1h)));
        }
        if (chart4h != null) {
            mediaList.add(new org.springframework.ai.content.Media(pngType, new ByteArrayResource(chart4h)));
        }

        if (mediaList.isEmpty()) return null;

        // API要求stream=true，使用流式调用后收集完整结果
        StringBuilder sb = new StringBuilder();
        chatClient.prompt()
                .user(u -> u.text(prompt).media(mediaList.toArray(new org.springframework.ai.content.Media[0])))
                .stream()
                .content()
                .doOnNext(chunk -> {
                    if (chunk != null && !chunk.isEmpty()) sb.append(chunk);
                })
                .blockLast();
        return sb.toString();
    }

    private List<AgentVote> parseResponse(String response, int baseVolBps) {
        if (response == null || response.isBlank()) return defaultVotes("PARSE_ERROR");

        try {
            String json = JsonUtils.extractJson(response);
            JSONObject root = JSON.parseObject(json);

            JSONArray patterns = root.getJSONArray("patterns");
            if (patterns != null && !patterns.isEmpty()) {
                log.info("[Q3.chart] 识别到{}个形态: {}", patterns.size(),
                        patterns.stream().map(p -> ((JSONObject) p).getString("name")).toList());
            }

            JSONArray votes = root.getJSONArray("votes");
            if (votes == null || votes.size() < 3) return defaultVotes("INVALID_FORMAT");

            List<AgentVote> result = new ArrayList<>(3);
            for (int i = 0; i < 3 && i < votes.size(); i++) {
                JSONObject v = votes.getJSONObject(i);
                String horizon = v.getString("horizon");
                double score = Math.clamp(toDouble(v.get("score")), -1, 1);
                double conf = Math.clamp(toDouble(v.get("confidence")), 0, 1);

                List<String> reasons = v.get("reasonCodes") instanceof List<?> l
                        ? l.stream().map(String::valueOf).toList() : List.of("CHART_ANALYSIS");

                Direction dir = Math.abs(score) < 0.05 ? Direction.NO_TRADE
                        : (score > 0 ? Direction.LONG : Direction.SHORT);
                int moveBps = (int) (Math.abs(score) * baseVolBps * 0.4);

                result.add(new AgentVote(name(), horizon, dir, score, conf,
                        moveBps, baseVolBps, reasons, List.of()));
            }
            return result;
        } catch (Exception e) {
            log.warn("[Q3.chart] 解析失败: {}", e.getMessage());
            return defaultVotes("PARSE_ERROR");
        }
    }

    private List<AgentVote> defaultVotes(String reason) {
        return List.of(
                AgentVote.noTrade(name(), "0_10", reason),
                AgentVote.noTrade(name(), "10_20", reason),
                AgentVote.noTrade(name(), "20_30", reason));
    }

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
}
