package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibservice.agent.quant.domain.LlmCallMode;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * LLM Regime审核节点：审核BuildFeaturesNode的regime检测结果。
 * 可修正regime、输出灰度置信度、预警regime转换信号。
 */
@Slf4j
public class RegimeReviewNode implements NodeAction {

    private static final int REVIEW_CALLS = 3;
    private static final double LOW_CONFIDENCE_STDDEV = 0.15;

    private final ChatClient chatClient;
    private final LlmCallMode callMode;
    private final MemoryService memoryService;

    private record ReviewCandidate(
            MarketRegime regime,
            double confidence,
            String reasoning,
            String transitionSignal,
            String transitionDetail
    ) {}

    public RegimeReviewNode(ChatClient.Builder builder, LlmCallMode callMode) {
        this(builder, callMode, null);
    }

    public RegimeReviewNode(ChatClient.Builder builder, LlmCallMode callMode, MemoryService memoryService) {
        this.chatClient = builder.build();
        this.callMode = callMode;
        this.memoryService = memoryService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        FeatureSnapshot snapshot =
                (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        var indicatorMap = state.value("indicator_map").orElse(null);
        var priceChangeMap = state.value("price_change_map").orElse(null);

        if (snapshot == null) {
            log.warn("[Q2.5] feature_snapshot为空，跳过regime审核");
            return Map.of();
        }

        MarketRegime ruleRegime = snapshot.regime();
        log.info("[Q2.5.0] regime_review开始 ruleRegime={} symbol={}", ruleRegime, snapshot.symbol());

        try {
            String prompt = buildPrompt(snapshot, indicatorMap, priceChangeMap);
            List<ReviewCandidate> reviews = new ArrayList<>(REVIEW_CALLS);
            for (int i = 1; i <= REVIEW_CALLS; i++) {
                try {
                    String response = callMode.call(chatClient, prompt);
                    ReviewCandidate review = parseReview(response, ruleRegime);
                    if (review != null) {
                        reviews.add(review);
                    }
                    log.info("[Q2.5.1] LLM审核样本 {}/{} 返回 {}chars",
                            i, REVIEW_CALLS, response != null ? response.length() : 0);
                } catch (Exception e) {
                    log.warn("[Q2.5] LLM regime审核样本 {}/{} 失败: {}", i, REVIEW_CALLS, e.getMessage());
                }
            }

            return applyReview(snapshot, ruleRegime, reviews, startMs);
        } catch (Exception e) {
            log.warn("[Q2.5] LLM regime审核失败，保留规则检测结果: {}", e.getMessage());
            return rebuildWithDefaults(snapshot);
        }
    }

    private String buildPrompt(FeatureSnapshot snapshot, Object indicatorMap, Object priceChangeMap) {
        // 多周期指标摘要
        StringBuilder indicatorBlock = new StringBuilder();
        for (String tf : List.of("1m", "5m", "15m", "1h", "4h", "1d")) {
            Map<String, Object> ind = snapshot.indicatorsByTimeframe().get(tf);
            if (ind == null) {
                indicatorBlock.append(tf).append(": 无数据\n");
                continue;
            }
            indicatorBlock.append(tf).append(": ");
            appendIfPresent(indicatorBlock, ind, "rsi14", "RSI");
            appendIfPresent(indicatorBlock, ind, "adx", "ADX");
            appendIfPresent(indicatorBlock, ind, "plus_di", "+DI");
            appendIfPresent(indicatorBlock, ind, "minus_di", "-DI");
            appendIfPresent(indicatorBlock, ind, "macd_cross", "MACD信号");
            appendIfPresent(indicatorBlock, ind, "macd_hist", "MACD柱");
            appendIfPresent(indicatorBlock, ind, "boll_bandwidth", "Boll带宽");
            Object maAlign = ind.get("ma_alignment");
            if (maAlign != null) {
                int ma = ((Number) maAlign).intValue();
                indicatorBlock.append("均线=").append(ma > 0 ? "多头" : ma < 0 ? "空头" : "缠绕").append(" ");
            }
            indicatorBlock.append("\n");
        }

        // 涨跌幅
        StringBuilder priceBlock = new StringBuilder();
        if (snapshot.priceChanges() != null) {
            for (var entry : snapshot.priceChanges().entrySet()) {
                priceBlock.append(entry.getKey()).append("=")
                        .append(entry.getValue().setScale(2, RoundingMode.HALF_UP)).append("% ");
            }
        }

        // 微结构摘要
        String microText = "合约盘口失衡=%.3f 现货盘口失衡=%.3f 主动买卖delta=%.3f 成交强度=%.1f 大单偏差=%.3f OI变化率=%.3f 资金费率偏离=%.3f 资金费率趋势=%.3f 多空比极端度=%.3f 基差=%.2fbps 现货强弱代理=%.3f"
                .formatted(snapshot.bidAskImbalance(), snapshot.spotBidAskImbalance(), snapshot.tradeDelta(),
                        snapshot.tradeIntensity(), snapshot.largeTradeBias(),
                        snapshot.oiChangeRate(),
                        snapshot.fundingDeviation(), snapshot.fundingRateTrend(), snapshot.lsrExtreme(),
                        snapshot.spotPerpBasisBps(), snapshot.spotLeadLagScore());

        String ivText = snapshot.toIvSummary();

        String qualityText = snapshot.qualityFlags() != null && !snapshot.qualityFlags().isEmpty()
                ? String.join(", ", snapshot.qualityFlags()) : "正常";

        return """
                你是加密货币市场状态分析师。系统通过规则（ADX/DI/ATR/Boll）检测到当前市场状态，请结合多周期指标审核其准确性。

                【规则检测结果】%s
                【当前价格】%s

                【多周期技术指标】
                %s
                【多周期涨跌幅】%s
                【微结构快照】%s
                【期权隐含波动率】%s
                【布林带收缩】%s   【ATR(5m)】%s
                【数据质量】%s

                【历史记忆】
                %s

                市场状态定义：
                - TREND_UP: ADX>25 + DI多头，价格沿趋势上行，回调是买入机会
                - TREND_DOWN: ADX>25 + DI空头，价格沿趋势下行，反弹是卖出机会
                - RANGE: ADX<20，价格在区间内震荡，适合均值回归
                - SQUEEZE: 布林带收缩+ADX<15，波动率压缩，等待方向选择
                - SHOCK: ATR突增>2倍历史均值，极端波动，优先降风险

                审核要点：
                1. 规则只看15m/5m的ADX/DI，但1h/4h可能讲不同的故事——多周期是否一致？
                2. ADX在20-25之间是灰色地带，结合涨跌幅和均线排列判断更像趋势还是震荡
                3. 当前状态是否处于转换临界点？（如趋势即将衰竭、挤压即将突破）
                4. 微结构信号（资金费率、OI、多空比）是否支持当前regime判断？
                5. 期权IV：价格平静但DVOL/ATM IV抬升→聪明钱布局；skew极端→方向性押注集中

                严格返回JSON（不要markdown包裹）：
                {
                  "confirmedRegime": "TREND_UP/TREND_DOWN/RANGE/SQUEEZE/SHOCK",
                  "confidence": 0.75,
                  "reasoning": "一段话解释判断依据",
                  "transitionSignal": "NONE/WEAKENING/STRENGTHENING/BREAKING_OUT/BREAKING_DOWN",
                  "transitionDetail": "转换信号的具体说明（如果有）"
                }

                约束：
                - confirmedRegime必须是上述5种之一
                - confidence范围[0,1]，表示对regime判断的把握程度
                - 如果规则检测结果合理，直接确认即可，不要为了改而改
                - transitionSignal表示regime是否即将转换，NONE=稳定
                - 参考历史记忆中的教训，避免重复犯同样的错误
                """.formatted(
                snapshot.regime().name(),
                snapshot.lastPrice() != null ? snapshot.lastPrice().toPlainString() : "未知",
                indicatorBlock.toString(), priceBlock.toString(), microText, ivText,
                snapshot.bollSqueeze() ? "是" : "否",
                snapshot.atr5m() != null ? snapshot.atr5m().toPlainString() : "无",
                qualityText,
                buildMemoryBlock(snapshot));
    }

    private Map<String, Object> applyReview(FeatureSnapshot snapshot, MarketRegime ruleRegime,
                                             List<ReviewCandidate> reviews, long startMs) {
        if (reviews == null || reviews.isEmpty()) {
            return rebuildWithDefaults(snapshot);
        }

        try {
            double confidence = medianConfidence(reviews);
            double confidenceStddev = confidenceStddev(reviews);
            ReviewCandidate selected = selectMedianReview(reviews, confidence);
            MarketRegime llmRegime = majorityRegime(reviews, selected.regime());
            String transitionSignal = selected.transitionSignal();
            if (transitionSignal == null || transitionSignal.isBlank()) transitionSignal = "NONE";

            boolean lowConfidence = confidenceStddev > LOW_CONFIDENCE_STDDEV || reviews.size() < REVIEW_CALLS;
            log.info("[Q2.5.2] LLM审核 rule={} llm={} conf={} stddev={} lowConfidence={} transition={} reasoning={}",
                    ruleRegime, llmRegime, String.format("%.2f", confidence),
                    String.format("%.3f", confidenceStddev), lowConfidence,
                    transitionSignal, selected.reasoning());

            Map<String, Object> result = new HashMap<>();
            result.put("regime_confidence", confidence);
            result.put("regime_confidence_stddev", confidenceStddev);
            result.put("regime_transition", transitionSignal);
            if (selected.transitionDetail() != null && !selected.transitionDetail().isBlank()) {
                result.put("regime_transition_detail", selected.transitionDetail());
            }

            // 确定最终regime
            MarketRegime finalRegime = llmRegime;
            if (llmRegime != ruleRegime) {
                // 安全约束：SHOCK只能由规则触发（ATR突增是客观事实），LLM不能凭空升级为SHOCK
                if (llmRegime == MarketRegime.SHOCK && ruleRegime != MarketRegime.SHOCK) {
                    log.info("[Q2.5.2] LLM建议SHOCK但规则未检测到ATR突增，忽略修正");
                    finalRegime = ruleRegime;
                } else {
                    log.info("[Q2.5.3] regime修正 {}→{}", ruleRegime, llmRegime);
                }
            }

            List<String> flags = new ArrayList<>(snapshot.qualityFlags() != null ? snapshot.qualityFlags() : List.of());
            if (lowConfidence && !flags.contains("LOW_CONFIDENCE")) {
                flags.add("LOW_CONFIDENCE");
            }

            FeatureSnapshot updated = snapshot.withRegimeReview(
                    finalRegime, List.copyOf(flags), confidence, transitionSignal);
            result.put("feature_snapshot", updated);

            log.info("[Q2.5.end] regime_review完成 总耗时{}ms", System.currentTimeMillis() - startMs);
            return result;
        } catch (Exception e) {
            log.warn("[Q2.5] LLM输出解析失败，保留规则regime: {}", e.getMessage());
            return rebuildWithDefaults(snapshot);
        }
    }

    private ReviewCandidate parseReview(String llmResponse, MarketRegime ruleRegime) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return null;
        }

        String json = JsonUtils.extractJson(llmResponse);
        JSONObject root = JSON.parseObject(json);

        String confirmedStr = root.getString("confirmedRegime");
        double confidence = Math.max(0, Math.min(1, root.getDoubleValue("confidence")));
        String transitionSignal = root.getString("transitionSignal");
        if (transitionSignal == null || transitionSignal.isBlank()) transitionSignal = "NONE";

        return new ReviewCandidate(
                parseRegime(confirmedStr, ruleRegime),
                confidence,
                root.getString("reasoning"),
                transitionSignal,
                root.getString("transitionDetail"));
    }

    private double medianConfidence(List<ReviewCandidate> reviews) {
        List<Double> values = reviews.stream()
                .map(ReviewCandidate::confidence)
                .sorted()
                .toList();
        return values.get(values.size() / 2);
    }

    private ReviewCandidate selectMedianReview(List<ReviewCandidate> reviews, double medianConfidence) {
        ReviewCandidate selected = reviews.getFirst();
        double bestDistance = Math.abs(selected.confidence() - medianConfidence);
        for (ReviewCandidate review : reviews) {
            double distance = Math.abs(review.confidence() - medianConfidence);
            if (distance < bestDistance) {
                selected = review;
                bestDistance = distance;
            }
        }
        return selected;
    }

    private MarketRegime majorityRegime(List<ReviewCandidate> reviews, MarketRegime fallback) {
        Map<MarketRegime, Long> counts = reviews.stream()
                .collect(java.util.stream.Collectors.groupingBy(ReviewCandidate::regime, java.util.stream.Collectors.counting()));
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(fallback);
    }

    private double confidenceStddev(List<ReviewCandidate> reviews) {
        if (reviews.size() < 2) {
            return 0;
        }
        double avg = reviews.stream().mapToDouble(ReviewCandidate::confidence).average().orElse(0);
        double variance = reviews.stream()
                .mapToDouble(r -> Math.pow(r.confidence() - avg, 2))
                .average().orElse(0);
        return Math.sqrt(variance);
    }

    private Map<String, Object> rebuildWithDefaults(FeatureSnapshot snapshot) {
        // 追加fallback标记，让下游能区分"审核失败的默认值"和"真正的中等置信度"
        List<String> flags = new ArrayList<>(
                snapshot.qualityFlags() != null ? snapshot.qualityFlags() : List.of());
        flags.add("REGIME_REVIEW_FALLBACK");

        FeatureSnapshot updated = snapshot.withRegimeReview(
                snapshot.regime(), List.copyOf(flags), 0.5, "NONE");
        return Map.of("feature_snapshot", updated,
                "regime_confidence", 0.5, "regime_transition", "NONE");
    }

    private MarketRegime parseRegime(String str, MarketRegime fallback) {
        if (str == null) return fallback;
        return switch (str.toUpperCase().trim()) {
            case "TREND_UP" -> MarketRegime.TREND_UP;
            case "TREND_DOWN" -> MarketRegime.TREND_DOWN;
            case "RANGE" -> MarketRegime.RANGE;
            case "SQUEEZE" -> MarketRegime.SQUEEZE;
            case "SHOCK" -> MarketRegime.SHOCK;
            default -> fallback;
        };
    }

    private String buildMemoryBlock(FeatureSnapshot snapshot) {
        if (memoryService == null) return "暂无历史记忆";
        try {
            return memoryService.buildMemoryContext(snapshot.symbol(), snapshot.regime().name());
        } catch (Exception e) {
            log.warn("[Q2.5] 记忆查询失败: {}", e.getMessage());
            return "暂无历史记忆";
        }
    }

    private void appendIfPresent(StringBuilder sb, Map<String, Object> map, String key, String label) {
        Object v = map.get(key);
        if (v == null) return;
        if (v instanceof BigDecimal bd) {
            sb.append(label).append("=").append(bd.setScale(2, RoundingMode.HALF_UP)).append(" ");
        } else if (v instanceof Number n) {
            sb.append(label).append("=").append(String.format("%.2f", n.doubleValue())).append(" ");
        } else {
            sb.append(label).append("=").append(v).append(" ");
        }
    }
}
