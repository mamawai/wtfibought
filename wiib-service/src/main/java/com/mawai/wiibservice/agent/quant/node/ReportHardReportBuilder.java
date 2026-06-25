package com.mawai.wiibservice.agent.quant.node;

import com.mawai.wiibservice.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.Direction;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibservice.agent.quant.domain.HorizonForecast;
import com.mawai.wiibservice.agent.quant.domain.MacroContext;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.quant.domain.NewsItem;
import com.mawai.wiibservice.agent.quant.factor.NewsEventAgent;
import com.mawai.wiibservice.agent.quant.judge.ConsensusJudge;
import com.mawai.wiibservice.agent.quant.service.FactorWeightOverrideService;
import com.mawai.wiibservice.agent.quant.util.NewsRelevance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class ReportHardReportBuilder {

    private final FactorWeightOverrideService weightOverrideService;

    ReportHardReportBuilder(FactorWeightOverrideService weightOverrideService) {
        this.weightOverrideService = weightOverrideService;
    }

    CryptoAnalysisReport build(String symbol,
                               List<HorizonForecast> forecasts,
                               List<AgentVote> votes,
                               List<NewsEventAgent.FilteredNewsItem> filteredNews,
                               String overallDecision,
                               String riskStatus,
                               FeatureSnapshot snapshot,
                               MacroContext macroContext,
                               Map<String, Object[]> debateProbs) {
        CryptoAnalysisReport report = new CryptoAnalysisReport();
        report.setSummary(buildSummary(symbol, forecasts, overallDecision, riskStatus, snapshot));
        report.setAnalysisBasis(buildAnalysisBasis(forecasts, votes, overallDecision, riskStatus, snapshot));
        report.setDirection(buildDirectionInfo(forecasts, votes, debateProbs,
                snapshot != null ? snapshot.regime() : null));
        report.setKeyLevels(buildKeyLevels(snapshot));
        report.setIndicators(buildIndicatorsSummary(snapshot));
        report.setImportantNews(buildImportantNews(snapshot, votes, filteredNews));
        report.setPositionAdvice(buildPositionAdvice(forecasts));
        report.setRiskWarnings(buildRiskWarnings(forecasts, riskStatus, snapshot));
        report.setConfidence(calculateConfidence(forecasts, calculateAvgConfidence(forecasts)));
        report.setMacroContext(macroContext != null ? macroContext.toReportBlock() : "宏观上下文预热中，暂不介入交易。");
        report.setVolProfile(buildVolProfile(macroContext));
        return report;
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

    /** 从 MacroContext 提取波动画像（vol 主腿，agent 唯一被验证 skilled）。 */
    private List<CryptoAnalysisReport.VolProfile> buildVolProfile(MacroContext macroContext) {
        if (macroContext == null) return List.of();
        List<CryptoAnalysisReport.VolProfile> list = new ArrayList<>();
        for (ForecastHorizon h : List.of(ForecastHorizon.H6, ForecastHorizon.H12, ForecastHorizon.H24)) {
            MacroContext.Leg leg = macroContext.legs().get(h);
            if (leg == null) continue;
            CryptoAnalysisReport.VolProfile vp = new CryptoAnalysisReport.VolProfile();
            vp.setHorizon(h.name());
            vp.setVolState(leg.volTier().name());
            vp.setExpectedMoveBps(leg.expectedMoveBps());
            vp.setTrailingPercentile(leg.trailingPercentile());
            vp.setRiskBudgetHint(leg.riskBudgetHint());
            list.add(vp);
        }
        return list;
    }

    private CryptoAnalysisReport.DirectionInfo buildDirectionInfo(List<HorizonForecast> forecasts,
                                                                  List<AgentVote> votes,
                                                                  Map<String, Object[]> debateProbs,
                                                                  MarketRegime regime) {
        CryptoAnalysisReport.DirectionInfo direction = new CryptoAnalysisReport.DirectionInfo();
        direction.setUltraShort(formatDirectionWithProb(findForecast(forecasts, "H6"), votes, "H6", debateProbs, regime));
        direction.setShortTerm(formatDirectionWithProb(findForecast(forecasts, "H12"), votes, "H12", debateProbs, regime));
        direction.setMid(formatDirectionWithProb(findForecast(forecasts, "H24"), votes, "H24", debateProbs, regime));
        direction.setLongTerm("观望");
        return direction;
    }

    private CryptoAnalysisReport.KeyLevels buildKeyLevels(FeatureSnapshot snapshot) {
        CryptoAnalysisReport.KeyLevels levels = new CryptoAnalysisReport.KeyLevels();
        List<String> support = new ArrayList<>(3);
        List<String> resistance = new ArrayList<>(3);

        BigDecimal lastPrice = snapshot != null ? snapshot.lastPrice() : null;
        BigDecimal baseStep = snapshot != null
                ? (snapshot.atr() != null ? snapshot.atr() : snapshot.atr1m())
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
        for (String horizon : List.of("H6", "H12", "H24")) {
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

    private int calculateAvgConfidence(List<HorizonForecast> forecasts) {
        if (forecasts == null || forecasts.isEmpty()) {
            return 0;
        }
        double confidence = forecasts.stream()
                .filter(f -> f.direction() != Direction.NO_TRADE)
                .mapToDouble(HorizonForecast::confidence)
                .max()
                .orElse(forecasts.stream().mapToDouble(HorizonForecast::confidence).average().orElse(0));
        return (int) (confidence * 100);
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

    /**
     * 概率分布格式的方向展示。
     * 辩论概率修正优先：DebateJudge挖掘死数据之外的深层信号后给出的概率。
     * 无辩论概率时回退到Agent投票加权计算。
     */
    private String formatDirectionWithProb(HorizonForecast forecast, List<AgentVote> allVotes,
                                           String horizon, Map<String, Object[]> debateProbs,
                                           MarketRegime regime) {
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
            double w = ConsensusJudge.baseEvidenceWeight(v.agent(), horizon);
            if (weightOverrideService != null) {
                w = weightOverrideService.apply(v.agent(), horizon, regime, w);
            }
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
            case "H6" -> "H6(6h)";
            case "H12" -> "H12(12h)";
            case "H24" -> "H24(24h)";
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
                        || flag.equals("NO_ATR")
                        || flag.equals("NO_BOLL")
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
}
