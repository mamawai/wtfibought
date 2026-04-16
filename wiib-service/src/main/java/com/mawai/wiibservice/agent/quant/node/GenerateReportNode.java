package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibservice.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibservice.agent.quant.domain.*;
import com.mawai.wiibservice.agent.quant.factor.NewsEventAgent;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import com.mawai.wiibservice.agent.quant.util.NewsRelevance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 报告生成节点（LLM）。
 * 输入：结构化的ForecastResult数据（区间裁决、Agent投票、风控结果）。
 * 输出：CryptoAnalysisReport（前端兼容格式）。
 */
@Slf4j
public class GenerateReportNode implements NodeAction {

    private final ChatClient chatClient;
    private final LlmCallMode callMode;
    private final MemoryService memoryService;

    public GenerateReportNode(ChatClient.Builder builder, LlmCallMode callMode) {
        this(builder, callMode, null);
    }

    public GenerateReportNode(ChatClient.Builder builder, LlmCallMode callMode, MemoryService memoryService) {
        this.chatClient = builder.build();
        this.callMode = callMode;
        this.memoryService = memoryService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        String symbol = (String) state.value("target_symbol").orElse("BTCUSDT");
        log.info("[Q6.0] generate_report开始 symbol={}", symbol);
        List<HorizonForecast> forecasts =
                (List<HorizonForecast>) state.value("horizon_forecasts").orElse(List.of());
        List<AgentVote> votes =
                (List<AgentVote>) state.value("agent_votes").orElse(List.of());
        List<NewsEventAgent.FilteredNewsItem> filteredNews =
                (List<NewsEventAgent.FilteredNewsItem>) state.value("filtered_news").orElse(List.of());
        String overallDecision = (String) state.value("overall_decision").orElse("FLAT");
        String riskStatus = (String) state.value("risk_status").orElse("UNKNOWN");
        FeatureSnapshot snapshot =
                (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        String indicators = StateHelper.stateJson(state, "indicator_map");
        String priceChanges = StateHelper.stateJson(state, "price_change_map");
        int avgConfidence = forecasts.isEmpty() ? 0
                : (int) (forecasts.stream()
                    .filter(f -> f.direction() != Direction.NO_TRADE)
                    .mapToDouble(HorizonForecast::confidence)
                    .max().orElse(forecasts.stream().mapToDouble(HorizonForecast::confidence).average().orElse(0)) * 100);
        @SuppressWarnings("unchecked")
        Map<String, Object[]> debateProbs =
                (Map<String, Object[]>) state.value("debate_probs").orElse(Map.of());

        // ===== Step1: 硬性报告（纯计算，零LLM） =====
        CryptoAnalysisReport hardReport = buildHardReport(
                symbol, forecasts, votes, filteredNews, overallDecision, riskStatus, snapshot, avgConfidence, debateProbs);
        log.info("[Q6.1] 硬性报告构建完成 dirs=[{}/{}/{}] confidence={} positions={} news={} warnings={}",
                hardReport.getDirection().getUltraShort(), hardReport.getDirection().getShortTerm(),
                hardReport.getDirection().getMid(), hardReport.getConfidence(),
                hardReport.getPositionAdvice().size(), hardReport.getImportantNews().size(),
                hardReport.getRiskWarnings().size());

        // ===== Step2: LLM综合推理 =====
        CryptoAnalysisReport finalReport;
        String debateSummaryRaw = (String) state.value("debate_summary").orElse(null);
        try {
            String voteSummary = buildVoteSummary(votes);
            String memorySummary = buildMemorySummary(snapshot);
            String prompt = buildLlmPrompt(hardReport, voteSummary, indicators, priceChanges,
                    filteredNews, riskStatus, snapshot, debateSummaryRaw, memorySummary);
            String response = callMode.call(chatClient, prompt);
            log.info("[Q6.2] LLM推理返回 {}chars 耗时{}ms",
                    response != null ? response.length() : 0, System.currentTimeMillis() - startMs);

            // ===== Step3: 合并→最终报告 =====
            finalReport = mergeLlmAnalysis(hardReport, response);
        } catch (Exception e) {
            log.warn("[Q6.2] LLM推理失败，最终报告=硬性报告: {}", e.getMessage());
            finalReport = hardReport;
        }

        // 辩论摘要写入报告
        if (debateSummaryRaw != null && !debateSummaryRaw.isBlank()) {
            try {
                JSONObject ds = JSON.parseObject(debateSummaryRaw);
                CryptoAnalysisReport.DebateSummary debate = new CryptoAnalysisReport.DebateSummary();
                debate.setBullArgument(ds.getString("bullArgument"));
                debate.setBearArgument(ds.getString("bearArgument"));
                debate.setJudgeReasoning(ds.getString("judgeReasoning"));
                finalReport.setDebateSummary(debate);
            } catch (Exception e) {
                log.warn("[Q6] 辩论摘要解析失败: {}", e.getMessage());
            }
        }

        if (!finalReport.isValid()) {
            log.error("[Q6] 报告关键字段缺失");
            return Map.of("data_available", false);
        }

        log.info("[Q6.3] 最终报告 summary={} reasoning={}chars",
                finalReport.getSummary(),
                finalReport.getReasoning() != null ? finalReport.getReasoning().length() : 0);

        ForecastResult forecastResult = new ForecastResult(symbol,
                (String) state.value("cycle_id").orElse("unknown"),
                snapshot != null ? snapshot.snapshotTime() : java.time.LocalDateTime.now(),
                forecasts, overallDecision, riskStatus, votes, snapshot, finalReport);

        log.info("[Q6.end] generate_report完成 总耗时{}ms", System.currentTimeMillis() - startMs);

        Map<String, Object> result = new HashMap<>();
        result.put("report", finalReport);
        result.put("hard_report", hardReport);
        // 序列化为JSON字符串，避免框架deepCopy把record转成Map
        result.put("forecast_result", com.alibaba.fastjson2.JSON.toJSONString(forecastResult));
        // 单独输出序列化后的JSON，避免ForecastResult反序列化时复杂record丢失
        if (snapshot != null) {
            result.put("raw_snapshot_json", com.alibaba.fastjson2.JSON.toJSONString(snapshot));
        }
        result.put("raw_report_json", com.alibaba.fastjson2.JSON.toJSONString(finalReport));
        return result;
    }

    private CryptoAnalysisReport buildHardReport(String symbol,
                                                 List<HorizonForecast> forecasts,
                                                 List<AgentVote> votes,
                                                 List<NewsEventAgent.FilteredNewsItem> filteredNews,
                                                 String overallDecision,
                                                 String riskStatus,
                                                 FeatureSnapshot snapshot,
                                                 int avgConfidence,
                                                 Map<String, Object[]> debateProbs) {
        CryptoAnalysisReport report = new CryptoAnalysisReport();
        report.setSummary(buildSummary(symbol, forecasts, overallDecision, riskStatus, snapshot));
        report.setAnalysisBasis(buildAnalysisBasis(forecasts, votes, overallDecision, riskStatus, snapshot));
        report.setDirection(buildDirectionInfo(forecasts, votes, debateProbs));
        report.setKeyLevels(buildKeyLevels(snapshot));
        report.setIndicators(buildIndicatorsSummary(snapshot));
        report.setImportantNews(buildImportantNews(snapshot, votes, filteredNews));
        report.setPositionAdvice(buildPositionAdvice(forecasts));
        report.setRiskWarnings(buildRiskWarnings(forecasts, riskStatus, snapshot));
        report.setConfidence(calculateConfidence(forecasts, avgConfidence));
        return report;
    }

    private String buildLlmPrompt(CryptoAnalysisReport hardReport,
                                   String voteSummary,
                                   String indicators,
                                   String priceChanges,
                                   List<NewsEventAgent.FilteredNewsItem> filteredNews,
                                   String riskStatus,
                                   FeatureSnapshot snapshot,
                                   String debateSummary,
                                   String memorySummary) {
        String directionBlock = "超短线(0-10min): %s\n短线(10-20min): %s\n中短线(20-30min): %s\n长线: 观望"
                .formatted(hardReport.getDirection().getUltraShort(),
                        hardReport.getDirection().getShortTerm(),
                        hardReport.getDirection().getMid());

        StringBuilder posBlock = new StringBuilder();
        for (CryptoAnalysisReport.PositionAdvice pa : hardReport.getPositionAdvice()) {
            posBlock.append(pa.getPeriod()).append(": ").append(pa.getType());
            if (!"NO_TRADE".equals(pa.getType())) {
                posBlock.append(" 入场=").append(pa.getEntry())
                        .append(" 止损=").append(pa.getStopLoss())
                        .append(" 止盈=").append(pa.getTakeProfit())
                        .append(" 风险收益比=").append(pa.getRiskReward());
            }
            posBlock.append("\n");
        }

        StringBuilder newsBlock = new StringBuilder();
        if (!filteredNews.isEmpty()) {
            for (NewsEventAgent.FilteredNewsItem item : filteredNews) {
                newsBlock.append("- [").append(item.sentiment()).append("/").append(item.impact()).append("] ")
                        .append(item.title()).append(" → ").append(item.reason()).append("\n");
            }
        } else {
            newsBlock.append("无重要新闻\n");
        }

        String regimeText = snapshot != null ? snapshot.regime().name() : "UNKNOWN";
        String qualityText = snapshot != null && snapshot.qualityFlags() != null && !snapshot.qualityFlags().isEmpty()
                ? String.join(", ", snapshot.qualityFlags()) : "正常";

        return """
                你是加密货币量化分析师。系统已完成全部结构化计算，请基于以下数据进行综合推理并撰写分析。

                【方向裁决（不可修改）】
                %s

                【入场建议（不可修改）】
                %s
                【关键价位】
                支撑: %s  阻力: %s

                【系统置信度】%d%%
                【风控状态】%s
                【市场状态】%s
                【数据质量】%s

                【Agent投票详情】
                %s

                【技术指标快照】%s
                【多周期涨跌幅】%s

                【重要新闻】
                %s

                【辩论裁决结论】
                %s

                【历史表现参考】
                %s

                你的任务：
                1. reasoning: 综合推理——分析各维度信号（动量/微结构/波动率/市场状态/新闻）的一致性与矛盾，解释裁决的合理性或局限性
                2. summary: 一句话总结当前市场状态和建议（用📈📉↔️标注方向）
                3. analysisBasis: 核心预测依据（2-3句话，说明为什么得出这个结论）
                4. indicators: 用通俗语言解读关键指标状态，关键术语首次出现时加简短注释如RSI=35（偏超卖）
                5. riskWarnings: 风险提示列表

                约束：
                - 方向判断、价位、入场建议已由量化系统确定，你只能解释不能修改
                - 如果裁决是观望/NO_TRADE，必须写观望
                - longTerm只能写"观望"
                - 不能新增系统未给出的概率、胜率或极端判断

                严格返回JSON（不要markdown包裹）：
                {
                  "reasoning": "综合推理分析",
                  "summary": "一句话总结",
                  "analysisBasis": "预测依据",
                  "indicators": "指标解读",
                  "riskWarnings": ["风险1", "风险2"]
                }
                """.formatted(
                directionBlock, posBlock.toString(),
                hardReport.getKeyLevels().getSupport(), hardReport.getKeyLevels().getResistance(),
                hardReport.getConfidence(), riskStatus, regimeText, qualityText,
                voteSummary, indicators, priceChanges, newsBlock.toString(),
                formatDebateSummary(debateSummary),
                memorySummary != null ? memorySummary : "");
    }

    private CryptoAnalysisReport mergeLlmAnalysis(CryptoAnalysisReport hardReport, String llmResponse) {
        CryptoAnalysisReport merged = new CryptoAnalysisReport();
        // 结构化字段：来自硬性报告（不可修改）
        merged.setDirection(hardReport.getDirection());
        merged.setKeyLevels(hardReport.getKeyLevels());
        merged.setPositionAdvice(hardReport.getPositionAdvice());
        merged.setImportantNews(hardReport.getImportantNews());
        merged.setConfidence(hardReport.getConfidence());

        if (llmResponse == null || llmResponse.isBlank()) {
            copyTextFields(merged, hardReport);
            return merged;
        }

        try {
            String json = JsonUtils.extractJson(llmResponse);
            JSONObject obj = JSON.parseObject(json);

            merged.setReasoning(getOrDefault(obj, "reasoning", null));
            merged.setSummary(getOrDefault(obj, "summary", hardReport.getSummary()));
            merged.setAnalysisBasis(getOrDefault(obj, "analysisBasis", hardReport.getAnalysisBasis()));
            merged.setIndicators(getOrDefault(obj, "indicators", hardReport.getIndicators()));

            JSONArray warnings = obj.getJSONArray("riskWarnings");
            if (warnings != null && !warnings.isEmpty()) {
                List<String> llmWarnings = new ArrayList<>();
                for (int i = 0; i < warnings.size(); i++) {
                    String w = warnings.getString(i);
                    if (w != null && !w.isBlank()) llmWarnings.add(w);
                }
                if (!llmWarnings.isEmpty()) {
                    merged.setRiskWarnings(llmWarnings);
                } else {
                    merged.setRiskWarnings(hardReport.getRiskWarnings());
                }
            } else {
                merged.setRiskWarnings(hardReport.getRiskWarnings());
            }
        } catch (Exception e) {
            log.warn("[Q6.merge] LLM输出解析失败，文本字段回退硬性报告: {}", e.getMessage());
            copyTextFields(merged, hardReport);
        }
        return merged;
    }

    private void copyTextFields(CryptoAnalysisReport target, CryptoAnalysisReport source) {
        target.setSummary(source.getSummary());
        target.setAnalysisBasis(source.getAnalysisBasis());
        target.setIndicators(source.getIndicators());
        target.setRiskWarnings(source.getRiskWarnings());
        target.setReasoning(source.getReasoning());
    }

    private String getOrDefault(JSONObject obj, String key, String defaultValue) {
        String val = obj.getString(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    /**
     * LLM 可能返回 importantNews 为字符串数组而非对象数组，统一归一化为对象数组。
     */
    private void normalizeImportantNews(JSONObject obj) {
        JSONArray arr = obj.getJSONArray("importantNews");
        if (arr == null || arr.isEmpty()) return;
        for (int i = 0; i < arr.size(); i++) {
            Object item = arr.get(i);
            if (item instanceof String s) {
                arr.set(i, new JSONObject(Map.of("title", s, "sentiment", "", "summary", s)));
            }
        }
    }

    private String buildVoteSummary(List<AgentVote> votes) {
        StringBuilder sb = new StringBuilder();
        for (AgentVote v : votes) {
            sb.append(v.agent()).append("[").append(v.horizon()).append("]: ")
                    .append(v.direction()).append(" score=").append(String.format("%.2f", v.score()))
                    .append(" conf=").append(String.format("%.2f", v.confidence()));
            if (!v.reasonCodes().isEmpty()) {
                sb.append(" reasons=").append(v.reasonCodes().stream().distinct().toList());
            }
            if (!v.riskFlags().isEmpty()) {
                sb.append(" flags=").append(v.riskFlags().stream().distinct().toList());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildSummary(String symbol,
                                List<HorizonForecast> forecasts,
                                String overallDecision,
                                String riskStatus,
                                FeatureSnapshot snapshot) {
        HorizonForecast best = findBestForecast(forecasts);
        if (best == null) {
            if (snapshot != null && snapshot.regime() == MarketRegime.SQUEEZE) {
                return "%s处于波动收缩阶段，当前没有明确优势交易窗口，优先等待突破确认。".formatted(symbol);
            }
            if (hasPartialData(snapshot)) {
                return "%s当前信号偏弱且存在数据缺口，本轮结果以谨慎观望为主。".formatted(symbol);
            }
            return "%s当前没有形成足够优势信号，整体以观望为主。".formatted(symbol);
        }

        String suffix = isStrictlyNormal(riskStatus) ? "可优先跟踪" : "但仍需等待确认";
        String dataHint = hasPartialData(snapshot) ? "本轮存在部分周期数据缺失，" : "";
        return "%s%s当前优先关注%s%s，其他区间以观望或低置信度信号为主，%s。"
                .formatted(symbol, dataHint, formatHorizon(best.horizon()), formatDirectionCn(best.direction()), suffix);
    }

    private String buildAnalysisBasis(List<HorizonForecast> forecasts,
                                      List<AgentVote> votes,
                                      String overallDecision,
                                      String riskStatus,
                                      FeatureSnapshot snapshot) {
        HorizonForecast best = findBestForecast(forecasts);
        String regimeText = snapshot != null ? snapshot.regime().name() : "UNKNOWN";
        String newsBias = summarizeNewsBias(votes);
        String qualityText = snapshot != null && snapshot.qualityFlags() != null && !snapshot.qualityFlags().isEmpty()
                ? "，数据标记=" + String.join("/", snapshot.qualityFlags())
                : "";
        if (best == null) {
            return "结构化裁决结果为%s，风控状态=%s，当前市场状态=%s%s，未形成足够 edge 与收益空间，新闻偏向=%s。"
                    .formatted(overallDecision, riskStatus, regimeText, qualityText, newsBias);
        }
        return "结构化裁决结果为%s，优先区间=%s%s，置信度=%.0f%%，分歧度=%.2f，当前市场状态=%s，新闻偏向=%s%s。"
                .formatted(overallDecision, formatHorizon(best.horizon()), formatDirectionCn(best.direction()),
                        best.confidence() * 100, best.disagreement(), regimeText, newsBias, qualityText);
    }

    private CryptoAnalysisReport.DirectionInfo buildDirectionInfo(List<HorizonForecast> forecasts,
                                                                    List<AgentVote> votes,
                                                                    Map<String, Object[]> debateProbs) {
        CryptoAnalysisReport.DirectionInfo direction = new CryptoAnalysisReport.DirectionInfo();
        direction.setUltraShort(formatDirectionWithProb(findForecast(forecasts, "0_10"), votes, "0_10", debateProbs));
        direction.setShortTerm(formatDirectionWithProb(findForecast(forecasts, "10_20"), votes, "10_20", debateProbs));
        direction.setMid(formatDirectionWithProb(findForecast(forecasts, "20_30"), votes, "20_30", debateProbs));
        direction.setLongTerm("观望");
        return direction;
    }

    private CryptoAnalysisReport.KeyLevels buildKeyLevels(FeatureSnapshot snapshot) {
        CryptoAnalysisReport.KeyLevels levels = new CryptoAnalysisReport.KeyLevels();
        List<String> support = new ArrayList<>(3);
        List<String> resistance = new ArrayList<>(3);

        BigDecimal lastPrice = snapshot != null ? snapshot.lastPrice() : null;
        BigDecimal baseStep = snapshot != null
                ? (snapshot.atr5m() != null ? snapshot.atr5m() : snapshot.atr1m())
                : null;

        if (lastPrice == null || lastPrice.signum() <= 0) {
            levels.setSupport(List.of("-", "-", "-"));
            levels.setResistance(List.of("-", "-", "-"));
            return levels;
        }

        if (baseStep == null || baseStep.signum() <= 0) {
            baseStep = lastPrice.multiply(BigDecimal.valueOf(0.003));
        }

        for (int i = 1; i <= 3; i++) {
            BigDecimal step = baseStep.multiply(BigDecimal.valueOf(i));
            support.add(formatPrice(lastPrice.subtract(step)));
            resistance.add(formatPrice(lastPrice.add(step)));
        }
        levels.setSupport(support);
        levels.setResistance(resistance);
        return levels;
    }

    private String buildIndicatorsSummary(FeatureSnapshot snapshot) {
        if (snapshot == null || snapshot.indicatorsByTimeframe() == null || snapshot.indicatorsByTimeframe().isEmpty()) {
            return "指标数据不可用。";
        }

        List<String> parts = new ArrayList<>();
        parts.add("市场状态=%s，代表当前价格更偏向%s"
                .formatted(snapshot.regime(), describeRegime(snapshot.regime())));
        appendTimeframeIndicators(parts, snapshot.indicatorsByTimeframe(), "5m");
        appendTimeframeIndicators(parts, snapshot.indicatorsByTimeframe(), "1h");
        appendTimeframeIndicators(parts, snapshot.indicatorsByTimeframe(), "4h");
        if (snapshot.spotLastPrice() != null) {
            parts.add("现货联动: spot=%s basis=%.2fbps spot盘口=%.2f spot驱动代理=%.2f"
                    .formatted(snapshot.spotLastPrice().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            snapshot.spotPerpBasisBps(), snapshot.spotBidAskImbalance(), snapshot.spotLeadLagScore()));
        }
        if (snapshot.bollSqueeze()) {
            parts.add("5m布林带处于收缩状态，短线更适合等确认而不是抢跑");
        }
        if (snapshot.qualityFlags() != null && !snapshot.qualityFlags().isEmpty()) {
            parts.add("当前数据质量标记=%s".formatted(String.join("/", snapshot.qualityFlags())));
        }
        return String.join("；", parts);
    }

    private void appendTimeframeIndicators(List<String> parts,
                                           Map<String, Map<String, Object>> indicators,
                                           String timeframe) {
        Map<String, Object> data = indicators.get(timeframe);
        if (data == null || data.isEmpty()) {
            return;
        }

        List<String> tfParts = new ArrayList<>();
        BigDecimal rsi = toBigDecimal(data.get("rsi14"));
        if (rsi != null) {
            tfParts.add("RSI=%s（%s）".formatted(
                    rsi.setScale(1, RoundingMode.HALF_UP).toPlainString(),
                    interpretRsi(rsi.doubleValue())));
        }
        String macdCross = asString(data.get("macd_cross"));
        if (macdCross != null && !macdCross.isBlank()) {
            tfParts.add("MACD=%s".formatted(interpretMacdCross(macdCross)));
        }
        BigDecimal hist = toBigDecimal(data.get("macd_hist"));
        if (hist != null) {
            tfParts.add("HIST=%s（%s）".formatted(
                    hist.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    interpretHistogram(hist.doubleValue())));
        }
        BigDecimal bandwidth = toBigDecimal(data.get("boll_bandwidth"));
        if (bandwidth != null) {
            tfParts.add("BOLL带宽=%s（%s）".formatted(
                    bandwidth.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    interpretBandwidth(bandwidth.doubleValue())));
        }
        int maAlignment = toInt(data.get("ma_alignment"));
        if (maAlignment > 0) {
            tfParts.add("均线多头");
        } else if (maAlignment < 0) {
            tfParts.add("均线空头");
        }
        if (!tfParts.isEmpty()) {
            parts.add("%s[%s]".formatted(timeframe, String.join(", ", tfParts)));
        }
    }

    private List<CryptoAnalysisReport.ImportantNews> buildImportantNews(
            FeatureSnapshot snapshot, List<AgentVote> votes,
            List<NewsEventAgent.FilteredNewsItem> filteredNews) {
        // 优先使用LLM筛选的新闻
        if (filteredNews != null && !filteredNews.isEmpty()) {
            LinkedHashSet<String> seenTitles = new LinkedHashSet<>();
            List<CryptoAnalysisReport.ImportantNews> items = new ArrayList<>();
            for (NewsEventAgent.FilteredNewsItem item : filteredNews) {
                if (item.title() == null || item.title().isBlank()) continue;
                String title = item.title().trim();
                if (!seenTitles.add(title)) continue;
                CryptoAnalysisReport.ImportantNews news = new CryptoAnalysisReport.ImportantNews();
                news.setTitle(title);
                String sentiment = switch (item.sentiment().toLowerCase()) {
                    case "bullish" -> "偏多";
                    case "bearish" -> "偏空";
                    default -> "中性";
                };
                news.setSentiment(sentiment);
                news.setSummary(item.reason());
                items.add(news);
                if (items.size() >= 5) break;
            }
            if (!items.isEmpty()) return items;
        }

        // fallback: 关键词过滤
        if (snapshot == null || snapshot.newsItems() == null || snapshot.newsItems().isEmpty()) {
            return List.of();
        }

        List<NewsItem> relevantNews = NewsRelevance.filterRelevant(snapshot.symbol(), snapshot.newsItems());
        LinkedHashSet<String> seenTitles = new LinkedHashSet<>();
        String newsBias = summarizeNewsBias(votes);
        List<CryptoAnalysisReport.ImportantNews> items = new ArrayList<>();
        for (NewsItem item : relevantNews) {
            if (item == null || item.title() == null || item.title().isBlank()) {
                continue;
            }
            String title = item.title().trim();
            if (!seenTitles.add(title)) {
                continue;
            }
            CryptoAnalysisReport.ImportantNews news = new CryptoAnalysisReport.ImportantNews();
            news.setTitle(title);
            String sentiment = classifyNewsSentiment(title, item.summary(), newsBias);
            news.setSentiment(sentiment);
            String summary = item.summary() != null && !item.summary().isBlank() ? item.summary().trim() : title;
            String implication = buildNewsImplication(sentiment);
            String merged = implication + " " + summary;
            news.setSummary(merged.length() > 180 ? merged.substring(0, 180) + "..." : merged);
            items.add(news);
            if (items.size() >= 5) {
                break;
            }
        }
        return items;
    }

    private List<CryptoAnalysisReport.PositionAdvice> buildPositionAdvice(List<HorizonForecast> forecasts) {
        List<CryptoAnalysisReport.PositionAdvice> adviceList = new ArrayList<>();
        for (String horizon : List.of("0_10", "10_20", "20_30")) {
            HorizonForecast forecast = findForecast(forecasts, horizon);
            CryptoAnalysisReport.PositionAdvice advice = new CryptoAnalysisReport.PositionAdvice();
            advice.setPeriod(formatHorizon(horizon));
            if (forecast == null || forecast.direction() == Direction.NO_TRADE) {
                advice.setType("NO_TRADE");
                advice.setEntry("-");
                advice.setStopLoss("-");
                advice.setTakeProfit("-");
                advice.setRiskReward("-");
            } else {
                advice.setType(forecast.direction().name());
                advice.setEntry(formatEntry(forecast));
                advice.setStopLoss(formatPrice(forecast.invalidationPrice()));
                advice.setTakeProfit(formatTargets(forecast));
                advice.setRiskReward(calculateRiskReward(forecast));
            }
            adviceList.add(advice);
        }
        return adviceList;
    }

    private List<String> buildRiskWarnings(List<HorizonForecast> forecasts,
                                           String riskStatus,
                                           FeatureSnapshot snapshot) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        boolean allNoTrade = forecasts == null || forecasts.stream().allMatch(f -> f.direction() == Direction.NO_TRADE);
        if (allNoTrade) {
            warnings.add("当前三个区间均未形成足够优势信号，强行交易的性价比偏低。");
        }
        if (statusContains(riskStatus, "ALL_NO_TRADE")) {
            warnings.add("量化裁决整体保持观望，说明当前 edge 与预期波动仍不足以支撑稳定开仓。");
        }
        if (statusContains(riskStatus, "HIGH_DISAGREEMENT") || statusContains(riskStatus, "CAUTIOUS")) {
            warnings.add("区间之间仍存在明显分歧，方向确认前不宜扩大仓位。");
        }
        if (statusContains(riskStatus, "PARTIAL_DATA")) {
            warnings.add("当前结论受数据完整性约束，若后续补齐关键周期数据，方向和仓位都可能被重新校准。");
        }
        if (snapshot != null && snapshot.regime() == MarketRegime.SQUEEZE) {
            warnings.add("市场处于 SQUEEZE 收缩状态，只有在突破或跌破被确认后才适合跟随。");
        }
        if (snapshot != null && snapshot.qualityFlags() != null && !snapshot.qualityFlags().isEmpty()) {
            warnings.add("当前输入数据存在质量标记：%s，这意味着部分短线判断可靠性会下降。"
                    .formatted(String.join(", ", snapshot.qualityFlags())));
        }
        if (warnings.isEmpty()) {
            warnings.add("仓位和杠杆应随置信度同步收缩，避免把低 edge 信号硬做成重仓交易。");
        }
        return new ArrayList<>(warnings);
    }

    private int calculateConfidence(List<HorizonForecast> forecasts, int avgConfidence) {
        if (forecasts == null || forecasts.isEmpty()) {
            return Math.max(15, avgConfidence);
        }
        int maxTradeConf = forecasts.stream()
                .filter(f -> f.direction() != Direction.NO_TRADE)
                .mapToInt(f -> (int) Math.round(f.confidence() * 100))
                .max()
                .orElse(0);
        if (maxTradeConf > 0) return maxTradeConf;
        // 全部NO_TRADE：取最高disagreement对应的"方向倾向度"，保底15%
        double maxScore = forecasts.stream()
                .mapToDouble(f -> Math.abs(f.weightedScore()))
                .max().orElse(0);
        return Math.max(15, (int) (maxScore * 60));
    }

    private HorizonForecast findBestForecast(List<HorizonForecast> forecasts) {
        if (forecasts == null) {
            return null;
        }
        HorizonForecast best = null;
        for (HorizonForecast forecast : forecasts) {
            if (forecast.direction() == Direction.NO_TRADE) {
                continue;
            }
            if (best == null || forecast.confidence() > best.confidence()) {
                best = forecast;
            }
        }
        return best;
    }

    private HorizonForecast findForecast(List<HorizonForecast> forecasts, String horizon) {
        if (forecasts == null) {
            return null;
        }
        for (HorizonForecast forecast : forecasts) {
            if (horizon.equals(forecast.horizon())) {
                return forecast;
            }
        }
        return null;
    }

    private String formatDirectionCn(HorizonForecast forecast) {
        return forecast == null ? "观望" : formatDirectionCn(forecast.direction());
    }

    private String formatDirectionCn(Direction direction) {
        if (direction == null) {
            return "观望";
        }
        return switch (direction) {
            case LONG -> "做多";
            case SHORT -> "做空";
            case NO_TRADE -> "观望";
        };
    }

    /** Agent权重表（与HorizonJudge一致） */
    private static final Map<String, Map<String, Double>> AGENT_WEIGHTS = Map.of(
            "microstructure", Map.of("0_10", 0.35, "10_20", 0.14, "20_30", 0.05),
            "momentum",       Map.of("0_10", 0.25, "10_20", 0.30, "20_30", 0.30),
            "regime",         Map.of("0_10", 0.15, "10_20", 0.20, "20_30", 0.25),
            "volatility",     Map.of("0_10", 0.15, "10_20", 0.16, "20_30", 0.15),
            "news_event",     Map.of("0_10", 0.10, "10_20", 0.20, "20_30", 0.25)
    );

    /**
     * 概率分布格式的方向展示。
     * 辩论概率修正优先：DebateJudge挖掘死数据之外的深层信号后给出的概率。
     * 无辩论概率时回退到Agent投票加权计算。
     */
    private String formatDirectionWithProb(HorizonForecast forecast, List<AgentVote> allVotes,
                                            String horizon, Map<String, Object[]> debateProbs) {
        // 辩论概率优先
        if (debateProbs != null && debateProbs.containsKey(horizon)) {
            // state序列化可能导致Integer[]变Object[]，安全取值
            Object[] raw = (Object[]) debateProbs.get(horizon);
            int bullPct = ((Number) raw[0]).intValue();
            int rangePct = ((Number) raw[1]).intValue();
            int bearPct = ((Number) raw[2]).intValue();

            // 确保与forecast方向一致：如果forecast有明确方向，概率中该方向必须最高
            if (forecast != null && forecast.direction() == Direction.LONG && bullPct <= bearPct) {
                int swap = bullPct;
                bullPct = bearPct;
                bearPct = swap;
            } else if (forecast != null && forecast.direction() == Direction.SHORT && bearPct <= bullPct) {
                int swap = bearPct;
                bearPct = bullPct;
                bullPct = swap;
            }

            if (forecast != null && forecast.direction() == Direction.LONG) {
                return "做多(%d%%) 震荡(%d%%) 偏空(%d%%)".formatted(bullPct, rangePct, bearPct);
            } else if (forecast != null && forecast.direction() == Direction.SHORT) {
                return "做空(%d%%) 震荡(%d%%) 偏多(%d%%)".formatted(bearPct, rangePct, bullPct);
            } else if (bearPct > bullPct) {
                return "偏空(%d%%) 震荡(%d%%) 偏多(%d%%)".formatted(bearPct, rangePct, bullPct);
            } else if (bullPct > bearPct) {
                return "偏多(%d%%) 震荡(%d%%) 偏空(%d%%)".formatted(bullPct, rangePct, bearPct);
            } else {
                return "震荡(%d%%) 偏多(%d%%) 偏空(%d%%)".formatted(rangePct, bullPct, bearPct);
            }
        }

        // 回退：从Agent投票计算
        double bullish = 0, bearish = 0;
        for (AgentVote v : allVotes) {
            if (!horizon.equals(v.horizon())) continue;
            double w = AGENT_WEIGHTS.getOrDefault(v.agent(), Map.of()).getOrDefault(horizon, 0.1);
            double contribution = w * Math.abs(v.score()) * Math.max(0.1, v.confidence());
            if (v.score() > 0.02) bullish += contribution;
            else if (v.score() < -0.02) bearish += contribution;
        }

        double totalSignal = bullish + bearish;
        if (totalSignal < 0.005) {
            return "震荡(80%) 偏多(10%) 偏空(10%)";
        }

        double bullRatio = bullish / totalSignal;
        double bearRatio = bearish / totalSignal;

        // 信号强度决定"震荡"占比：信号越弱，震荡越高
        double strength = Math.min(1.0, totalSignal * 3.0);
        int rangePct;
        if (forecast != null && forecast.direction() != Direction.NO_TRADE) {
            // 有明确方向时，震荡占比低
            rangePct = Math.max(10, (int) ((1 - strength) * 30));
        } else {
            // NO_TRADE时，震荡占比高
            rangePct = Math.max(20, (int) ((1 - strength) * 55));
        }

        int dirPct = 100 - rangePct;
        int bullPct = (int) (bullRatio * dirPct);
        int bearPct = dirPct - bullPct;

        // 有明确方向时，确保方向占比最高
        if (forecast != null && forecast.direction() == Direction.LONG && bullPct <= bearPct) {
            bullPct = bearPct + 5;
            bearPct = dirPct - bullPct;
        } else if (forecast != null && forecast.direction() == Direction.SHORT && bearPct <= bullPct) {
            bearPct = bullPct + 5;
            bullPct = dirPct - bearPct;
        }

        // 重新分配确保总和100%
        rangePct = 100 - bullPct - bearPct;

        // 格式：主导方向在前
        if (forecast != null && forecast.direction() == Direction.LONG) {
            return "做多(%d%%) 震荡(%d%%) 偏空(%d%%)".formatted(bullPct, rangePct, bearPct);
        } else if (forecast != null && forecast.direction() == Direction.SHORT) {
            return "做空(%d%%) 震荡(%d%%) 偏多(%d%%)".formatted(bearPct, rangePct, bullPct);
        } else if (bearPct > bullPct) {
            return "偏空(%d%%) 震荡(%d%%) 偏多(%d%%)".formatted(bearPct, rangePct, bullPct);
        } else if (bullPct > bearPct) {
            return "偏多(%d%%) 震荡(%d%%) 偏空(%d%%)".formatted(bullPct, rangePct, bearPct);
        } else {
            return "震荡(%d%%) 偏多(%d%%) 偏空(%d%%)".formatted(rangePct, bullPct, bearPct);
        }
    }

    private String formatHorizon(String horizon) {
        return switch (horizon) {
            case "0_10" -> "0-10min";
            case "10_20" -> "10-20min";
            case "20_30" -> "20-30min";
            default -> horizon;
        };
    }

    private String formatEntry(HorizonForecast forecast) {
        if (forecast.entryLow() == null || forecast.entryHigh() == null) {
            return "-";
        }
        return formatPrice(forecast.entryLow()) + " - " + formatPrice(forecast.entryHigh());
    }

    private String formatTargets(HorizonForecast forecast) {
        if (forecast.tp1() == null || forecast.tp2() == null) {
            return "-";
        }
        return formatPrice(forecast.tp1()) + " / " + formatPrice(forecast.tp2());
    }

    private String calculateRiskReward(HorizonForecast forecast) {
        if (forecast.entryLow() == null || forecast.entryHigh() == null
                || forecast.tp1() == null || forecast.invalidationPrice() == null) {
            return "-";
        }

        BigDecimal entryMid = forecast.entryLow().add(forecast.entryHigh())
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        BigDecimal reward = forecast.tp1().subtract(entryMid).abs();
        BigDecimal risk = entryMid.subtract(forecast.invalidationPrice()).abs();
        if (risk.signum() <= 0 || reward.signum() <= 0) {
            return "-";
        }
        BigDecimal rr = reward.divide(risk, 2, RoundingMode.HALF_UP);
        return "1:" + rr.stripTrailingZeros().toPlainString();
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) {
            return "-";
        }
        BigDecimal normalized;
        BigDecimal abs = price.abs();
        if (abs.compareTo(BigDecimal.valueOf(1000)) >= 0) {
            normalized = price.setScale(0, RoundingMode.HALF_UP);
        } else if (abs.compareTo(BigDecimal.ONE) >= 0) {
            normalized = price.setScale(2, RoundingMode.HALF_UP);
        } else {
            normalized = price.setScale(4, RoundingMode.HALF_UP);
        }
        return normalized.stripTrailingZeros().toPlainString();
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private String asString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private boolean hasPartialData(FeatureSnapshot snapshot) {
        return snapshot != null
                && snapshot.qualityFlags() != null
                && snapshot.qualityFlags().stream().anyMatch(flag ->
                flag.equals("PARTIAL_KLINE_DATA")
                        || flag.equals("NO_ATR_5M")
                        || flag.equals("NO_BOLL_5M")
                        || flag.startsWith("MISSING_TF_")
                        || flag.startsWith("INSUFFICIENT_BARS_"));
    }

    private String describeRegime(MarketRegime regime) {
        if (regime == null) {
            return "中性波动";
        }
        return switch (regime) {
            case TREND_UP -> "顺势做多或回踩接多";
            case TREND_DOWN -> "顺势做空或反弹承压后接空";
            case RANGE -> "区间震荡和均值回归";
            case SQUEEZE -> "等待突破确认";
            case SHOCK -> "极端波动，优先降风险";
        };
    }

    private String interpretRsi(double rsi) {
        if (rsi >= 70) return "偏强但接近超买，追涨性价比下降";
        if (rsi <= 30) return "偏弱且接近超卖，反弹概率抬升";
        if (rsi >= 55) return "偏强，买盘略占优";
        if (rsi <= 45) return "偏弱，卖压略占优";
        return "中性，方向优势不强";
    }

    private String interpretMacdCross(String macdCross) {
        return switch (macdCross) {
            case "golden" -> "金叉，趋势动能改善";
            case "death" -> "死叉，趋势动能转弱";
            default -> macdCross;
        };
    }

    private String interpretHistogram(double hist) {
        if (hist > 0) return "多头动能仍在";
        if (hist < 0) return "空头动能仍在";
        return "动能接近平衡";
    }

    private String interpretBandwidth(double bandwidth) {
        if (bandwidth < 1.5) return "波动压缩，容易等待方向选择";
        if (bandwidth > 5.0) return "波动扩张，价格弹性较大";
        return "波动正常";
    }

    private String summarizeNewsBias(List<AgentVote> votes) {
        if (votes == null || votes.isEmpty()) {
            return "中性";
        }
        double score = 0;
        double weight = 0;
        for (AgentVote vote : votes) {
            if (!"news_event".equals(vote.agent())) {
                continue;
            }
            double w = Math.max(0.1, vote.confidence());
            score += vote.score() * w;
            weight += w;
        }
        if (weight == 0) {
            return "中性";
        }
        double avg = score / weight;
        if (avg > 0.12) return "偏多";
        if (avg < -0.12) return "偏空";
        return "中性";
    }

    private String classifyNewsSentiment(String title, String summary, String fallbackBias) {
        String text = ((title == null ? "" : title) + " " + (summary == null ? "" : summary)).toLowerCase();
        int bullishScore = countMatches(text,
                "recovery", "bull", "accumulation", "accumulate", "inflow", "inflows",
                "approve", "approval", "etf", "break above", "breakout", "trendline break",
                "fuel", "outlook", "$80k", "stabiliz", "demand", "buy", "rally");
        int bearishScore = countMatches(text,
                "sell", "sellers", "short", "decline", "tighten grip", "struggling",
                "down", "plummet", "falls below", "pressure", "exodus", "bear",
                "liquidation", "market alert", "critical", "risk");

        if (bullishScore - bearishScore >= 2) {
            return "偏多";
        }
        if (bearishScore - bullishScore >= 2) {
            return "偏空";
        }
        if (bullishScore > bearishScore) {
            return "偏多";
        }
        if (bearishScore > bullishScore) {
            return "偏空";
        }
        return fallbackBias;
    }

    private int countMatches(String text, String... keywords) {
        int score = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    private String buildNewsImplication(String sentiment) {
        return switch (sentiment) {
            case "偏多" -> "偏多解读：代表情绪或资金面改善，短线更容易支撑价格。";
            case "偏空" -> "偏空解读：代表抛压或风险偏好转弱，短线更容易压制反弹。";
            default -> "中性解读：更多提供背景信息，对短线单边方向的指向有限。";
        };
    }

    private boolean statusContains(String riskStatus, String expected) {
        if (riskStatus == null || riskStatus.isBlank()) {
            return false;
        }
        for (String token : riskStatus.split(",")) {
            if (expected.equals(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isStrictlyNormal(String riskStatus) {
        return statusContains(riskStatus, "NORMAL")
                && !statusContains(riskStatus, "CAUTIOUS")
                && !statusContains(riskStatus, "HIGH_DISAGREEMENT")
                && !statusContains(riskStatus, "ALL_NO_TRADE")
                && !statusContains(riskStatus, "PARTIAL_DATA");
    }

    private String formatDebateSummary(String debateSummary) {
        if (debateSummary == null || debateSummary.isBlank()) return "无辩论记录";
        try {
            JSONObject obj = JSON.parseObject(debateSummary);
            return "Bull论据: " + obj.getString("bullArgument") +
                    "\nBear论据: " + obj.getString("bearArgument") +
                    "\n裁判结论: " + obj.getString("judgeReasoning");
        } catch (Exception e) {
            return debateSummary;
        }
    }

    private String buildMemorySummary(FeatureSnapshot snapshot) {
        if (memoryService == null || snapshot == null) return "";
        try {
            return memoryService.buildAccuracySummary(snapshot.symbol());
        } catch (Exception e) {
            return "";
        }
    }
}
