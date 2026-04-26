package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class MomentumAgent implements FactorAgent {

    @Override
    public String name() { return "momentum"; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot s) {
        Map<String, Map<String, Object>> indicators = s.indicatorsByTimeframe();
        if (indicators == null || indicators.isEmpty()) {
            return List.of(
                    AgentVote.noTrade(name(), "0_10", "NO_DATA"),
                    AgentVote.noTrade(name(), "10_20", "NO_DATA"),
                    AgentVote.noTrade(name(), "20_30", "NO_DATA"));
        }

        List<String> qualityFlags = s.qualityFlags() != null ? s.qualityFlags() : List.of();
        MarketRegime regime = s.regime() != null ? s.regime() : MarketRegime.RANGE;

        // 对3个时间尺度分别做动量分析
        // 0-10min 主要看 1m/5m
        TimeframePair pair0 = resolvePair(indicators, qualityFlags, "0_10", "1m", "5m");
        double score0 = calcTimeframeScore(indicators, pair0.primary(), pair0.secondary(), regime);
        List<String> reasons0 = collectReasons(indicators, pair0);

        // 10-20min 主要看 5m/15m
        TimeframePair pair1 = resolvePair(indicators, qualityFlags, "10_20", "5m", "15m");
        double score1 = calcTimeframeScore(indicators, pair1.primary(), pair1.secondary(), regime);
        List<String> reasons1 = collectReasons(indicators, pair1);

        // 20-30min 主要看 15m/1h
        TimeframePair pair2 = resolvePair(indicators, qualityFlags, "20_30", "15m", "1h");
        double score2 = calcTimeframeScore(indicators, pair2.primary(), pair2.secondary(), regime);
        List<String> reasons2 = collectReasons(indicators, pair2);

        // 多周期一致性检查: 如果短中长方向一致，加分
        double consistency = calcConsistency(indicators);
        score0 += consistency * 0.15;
        score1 += consistency * 0.20;
        score2 += consistency * 0.25;

        BigDecimal lastPrice = s.lastPrice();
        int volBps = s.atr5m() != null && lastPrice != null && lastPrice.signum() > 0
                ? s.atr5m().multiply(BigDecimal.valueOf(10000))
                    .divide(lastPrice, 0, java.math.RoundingMode.HALF_UP).intValue()
                : 30;

        log.info("[Q3.momentum] scores[{},{},{}] consistency={} reasons={}|{}|{}",
                String.format("%.3f", score0), String.format("%.3f", score1),
                String.format("%.3f", score2), String.format("%.2f", consistency),
                reasons0, reasons1, reasons2);

        return List.of(
                buildVote("0_10", score0, volBps, reasons0, pair0.fallbackUsed(), qualityFlags),
                buildVote("10_20", score1, volBps, reasons1, pair1.fallbackUsed(), qualityFlags),
                buildVote("20_30", score2, volBps, reasons2, pair2.fallbackUsed(), qualityFlags));
    }

    private double calcTimeframeScore(Map<String, Map<String, Object>> indicators,
                                       String primary, String secondary, MarketRegime regime) {
        double score = 0;
        Map<String, Object> p = indicators.get(primary);

        if (p != null) score += singleTfScore(p, regime) * 0.6;

        // fallback导致primary==secondary时，不重复计权
        if (!primary.equals(secondary)) {
            Map<String, Object> sec = indicators.get(secondary);
            if (sec != null) score += singleTfScore(sec, regime) * 0.4;
        }

        return clamp(score);
    }

    private double singleTfScore(Map<String, Object> ind, MarketRegime regime) {
        double score = 0;

        // 均线排列
        int maAlign = toInt(ind.get("ma_alignment"));
        score += maAlign * 0.25;

        // RSI — 阈值按 regime 动态调整
        BigDecimal rsi = toBd(ind.get("rsi14"));
        if (rsi != null) {
            double r = rsi.doubleValue();
            double obThresh = 70, osThresh = 30;
            if (regime == MarketRegime.TREND_UP || regime == MarketRegime.TREND_DOWN) {
                obThresh = 80; osThresh = 25; // 趋势市放宽超买超卖
            } else if (regime == MarketRegime.RANGE) {
                obThresh = 65; osThresh = 35; // 震荡市收紧
            }
            if (r > obThresh) score -= 0.2;
            else if (r < osThresh) score += 0.2;
            else if (r > 55) score += 0.1;
            else if (r < 45) score -= 0.1;
        }

        // MACD
        String cross = (String) ind.get("macd_cross");
        if ("golden".equals(cross)) score += 0.25;
        else if ("death".equals(cross)) score -= 0.25;

        BigDecimal hist = toBd(ind.get("macd_hist"));
        if (hist != null) {
            score += (hist.signum() > 0 ? 0.1 : -0.1);
        }

        String histTrend = (String) ind.get("macd_hist_trend");
        if (histTrend != null) {
            if (histTrend.startsWith("rising")) score += 0.1;
            else if (histTrend.startsWith("falling")) score -= 0.1;
        }

        // KDJ: K/D交叉 + J极值
        BigDecimal kdjK = toBd(ind.get("kdj_k"));
        BigDecimal kdjD = toBd(ind.get("kdj_d"));
        BigDecimal kdjJ = toBd(ind.get("kdj_j"));
        if (kdjK != null && kdjD != null) {
            if (kdjK.compareTo(kdjD) > 0) score += 0.1;       // K>D 金叉，偏多
            else if (kdjK.compareTo(kdjD) < 0) score -= 0.1;  // K<D 死叉，偏空
        }
        if (kdjJ != null) {
            double j = kdjJ.doubleValue();
            if (j > 80) score -= 0.1;       // J超买，反转信号
            else if (j < 20) score += 0.1;  // J超卖，反弹信号
        }

        // 量价配合
        BigDecimal volRatio = toBd(ind.get("volume_ratio"));
        String closeTrend = (String) ind.get("close_trend");
        if (volRatio != null && closeTrend != null) {
            boolean highVol = volRatio.doubleValue() > 1.3;
            if (closeTrend.contains("rising") && highVol) score += 0.15;
            else if (closeTrend.contains("falling") && highVol) score -= 0.15;
            else if (closeTrend.contains("rising") && !highVol) score += 0.03; // 缩量涨，弱
            else if (closeTrend.contains("falling") && !highVol) score -= 0.08; // 缩量跌，空头控盘
        }

        return score;
    }

    private double calcConsistency(Map<String, Map<String, Object>> indicators) {
        int bullCount = 0, bearCount = 0;
        for (String tf : List.of("1m", "5m", "15m", "1h")) {
            Map<String, Object> ind = indicators.get(tf);
            if (ind == null) continue;
            int align = toInt(ind.get("ma_alignment"));
            if (align > 0) bullCount++;
            else if (align < 0) bearCount++;
        }
        if (bullCount >= 3) return 0.5;
        if (bearCount >= 3) return -0.5;
        if (bullCount >= 2 && bullCount > bearCount) return 0.25;
        if (bearCount >= 2 && bearCount > bullCount) return -0.25;
        return 0;
    }

    private List<String> collectReasons(Map<String, Map<String, Object>> indicators, TimeframePair pair) {
        List<String> reasons = new ArrayList<>();
        if (pair.fallbackUsed()) {
            reasons.add("TF_FALLBACK_" + pair.horizon());
        }
        Map<String, Object> p = indicators.get(pair.primary());
        if (p != null) {
            int align = toInt(p.get("ma_alignment"));
            if (align == 1) reasons.add("MA_BULLISH_" + pair.primary().toUpperCase());
            else if (align == -1) reasons.add("MA_BEARISH_" + pair.primary().toUpperCase());

            String cross = (String) p.get("macd_cross");
            if ("golden".equals(cross)) reasons.add("MACD_GOLDEN_" + pair.primary().toUpperCase());
            else if ("death".equals(cross)) reasons.add("MACD_DEATH_" + pair.primary().toUpperCase());

            BigDecimal rsi = toBd(p.get("rsi14"));
            if (rsi != null) {
                if (rsi.doubleValue() > 70) reasons.add("RSI_OVERBOUGHT_" + pair.primary().toUpperCase());
                else if (rsi.doubleValue() < 30) reasons.add("RSI_OVERSOLD_" + pair.primary().toUpperCase());
            }
        }
        return reasons;
    }

    private TimeframePair resolvePair(Map<String, Map<String, Object>> indicators,
                                      List<String> qualityFlags,
                                      String horizon,
                                      String primary,
                                      String secondary) {
        boolean primaryPresent = indicators.containsKey(primary);
        boolean secondaryPresent = indicators.containsKey(secondary);
        if (primaryPresent && secondaryPresent) {
            return new TimeframePair(horizon, primary, secondary, false);
        }

        return switch (horizon) {
            case "0_10" -> new TimeframePair(horizon,
                    primaryPresent ? primary : fallback(indicators, "5m", "15m", "1h"),
                    secondaryPresent ? secondary : fallback(indicators, "5m", "15m", "1h"),
                    true);
            case "10_20" -> new TimeframePair(horizon,
                    primaryPresent ? primary : fallback(indicators, "1m", "15m", "1h"),
                    secondaryPresent ? secondary : fallback(indicators, "1h", "5m", "4h"),
                    true);
            case "20_30" -> new TimeframePair(horizon,
                    primaryPresent ? primary : fallback(indicators, "5m", "1h", "4h"),
                    secondaryPresent ? secondary : fallback(indicators, "4h", "5m", "1d"),
                    true);
            default -> new TimeframePair(horizon, primary, secondary, true);
        };
    }

    private String fallback(Map<String, Map<String, Object>> indicators, String... candidates) {
        for (String candidate : candidates) {
            if (indicators.containsKey(candidate)) {
                return candidate;
            }
        }
        return candidates.length > 0 ? candidates[0] : "1h";
    }

    private record TimeframePair(String horizon, String primary, String secondary, boolean fallbackUsed) {}

    private AgentVote buildVote(String horizon, double score, int volBps,
                                List<String> reasons, boolean fallbackUsed, List<String> qualityFlags) {
        double s = clamp(score);
        Direction dir = Math.abs(s) < 0.05 ? Direction.NO_TRADE : (s > 0 ? Direction.LONG : Direction.SHORT);
        double conf = Math.min(1.0, Math.abs(s) * 1.5);

        // fallback导致数据源退化 → 降信心
        if (fallbackUsed) conf *= 0.75;

        // 数据质量差 → 进一步衰减
        if (qualityFlags.contains("PARTIAL_KLINE_DATA")) conf *= 0.8;

        int moveBps = (int) (Math.abs(s) * volBps * 0.5);
        return new AgentVote(name(), horizon, dir, s, conf, moveBps, volBps, reasons, List.of());
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static BigDecimal toBd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private static double clamp(double v) { return Math.clamp(v, -1, 1); }
}
