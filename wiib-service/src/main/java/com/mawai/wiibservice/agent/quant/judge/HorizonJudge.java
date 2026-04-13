package com.mawai.wiibservice.agent.quant.judge;

import com.mawai.wiibservice.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 区间裁决器：对单个时间区间的全部AgentVote做加权汇总，输出HorizonForecast。
 * <p>
 * 裁决逻辑：
 * 1. 按Agent权重加权计算longScore和shortScore（weight × score，不乘confidence）
 * 2. 计算edge和disagreement
 * 3. 弱信号不判NO_TRADE，而是通过confidence衰减表达不确定性
 * 4. 只有全部agent无有效投票时才NO_TRADE
 */
@Slf4j
public class HorizonJudge {

    private static final double EPSILON = 1e-9;

    /** 初始权重表: agent → horizon → weight */
    private static final Map<String, Map<String, Double>> DEFAULT_WEIGHTS = Map.of(
            "microstructure", Map.of("0_10", 0.35, "10_20", 0.15, "20_30", 0.05),
            "momentum",       Map.of("0_10", 0.25, "10_20", 0.30, "20_30", 0.30),
            "regime",         Map.of("0_10", 0.15, "10_20", 0.20, "20_30", 0.25),
            "volatility",     Map.of("0_10", 0.15, "10_20", 0.15, "20_30", 0.15),
            "news_event",     Map.of("0_10", 0.10, "10_20", 0.20, "20_30", 0.25)
    );

    private final String horizon;
    private final Map<String, Map<String, Double>> agentAccuracy;

    public HorizonJudge(String horizon) { this(horizon, Map.of()); }

    public HorizonJudge(String horizon, Map<String, Map<String, Double>> agentAccuracy) {
        this.horizon = horizon;
        this.agentAccuracy = agentAccuracy != null ? agentAccuracy : Map.of();
    }

    public HorizonForecast judge(List<AgentVote> allVotes, BigDecimal lastPrice, List<String> qualityFlags) {
        List<AgentVote> filtered = allVotes.stream()
                .filter(v -> horizon.equals(v.horizon()))
                .filter(v -> !v.reasonCodes().contains("TIMEOUT")) // 超时票不参与裁决
                .toList();

        if (filtered.isEmpty()) {
            log.info("[Q4.judge] {} 无投票 → NO_TRADE", horizon);
            return HorizonForecast.noTrade(horizon, 1.0);
        }

        double longScore = 0;
        double shortScore = 0;
        double longMoveWeighted = 0;
        double shortMoveWeighted = 0;
        double longWeightSum = 0;
        double shortWeightSum = 0;

        // 独立波动聚合：所有agent（含score==0）都参与
        double totalVolWeighted = 0;
        double totalVolWeightSum = 0;
        // confidence加权均值，作为独立参考
        double confWeightedSum = 0;
        double confWeightDenom = 0;

        for (AgentVote vote : filtered) {
            double weight = getWeight(vote.agent(), horizon);

            // 方向聚合：weight × |score|（不乘confidence，避免三重衰减）
            double directionalWeighted = weight * Math.abs(vote.score());
            if (directionalWeighted > EPSILON) {
                int calibratedMoveBps = calibrateMoveBps(vote);

                if (vote.score() > 0) {
                    longScore += directionalWeighted;
                    longMoveWeighted += directionalWeighted * calibratedMoveBps;
                    longWeightSum += directionalWeighted;
                } else if (vote.score() < 0) {
                    shortScore += directionalWeighted;
                    shortMoveWeighted += directionalWeighted * calibratedMoveBps;
                    shortWeightSum += directionalWeighted;
                }
                // confidence独立聚合
                confWeightedSum += directionalWeighted * vote.confidence();
                confWeightDenom += directionalWeighted;
            }

            // 波动聚合：不管score是不是0，只要有volatilityBps就参与
            if (vote.volatilityBps() > 0) {
                double volatilityWeighted = weight * Math.max(vote.confidence(), 0.35);
                totalVolWeighted += volatilityWeighted * vote.volatilityBps();
                totalVolWeightSum += volatilityWeighted;
            }
        }

        if (longScore < EPSILON && shortScore < EPSILON) {
            log.info("[Q4.judge] {} 全部弱信号/无效票 → NO_TRADE", horizon);
            return HorizonForecast.noTrade(horizon, 1.0);
        }

        double edge = Math.abs(longScore - shortScore);
        double total = longScore + shortScore + EPSILON;
        double disagreement = 1.0 - edge / total;
        Direction direction = longScore >= shortScore ? Direction.LONG : Direction.SHORT;
        double dominantWeightSum = direction == Direction.LONG ? longWeightSum : shortWeightSum;
        int dominantMoveBps = dominantWeightSum > EPSILON
                ? (int) Math.round((direction == Direction.LONG ? longMoveWeighted : shortMoveWeighted) / dominantWeightSum)
                : 0;
        // volatilityBps来自全局波动聚合（含score==0的风险agent）
        int dominantVolBps = totalVolWeightSum > EPSILON
                ? (int) Math.round(totalVolWeighted / totalVolWeightSum)
                : 0;

        // agent平均confidence（独立参考）
        double avgAgentConf = confWeightDenom > EPSILON ? confWeightedSum / confWeightDenom : 0.3;

        // confidence = 方向占比为主，agent信心为辅，弱信号用减法惩罚（避免乘法级联衰减）
        double rawConf = (direction == Direction.LONG ? longScore : shortScore) / total;
        rawConf *= Math.clamp(0.5 + avgAgentConf, 0.70, 1.0);
        // 弱信号惩罚（减法，总计上限-0.18，避免叠加过深）
        double minEdge = getMinEdge(qualityFlags);
        int minMoveBps = getMinMoveBps();
        double totalPenalty = 0;
        if (edge < minEdge) totalPenalty += 0.08;
        if (dominantMoveBps < minMoveBps) totalPenalty += 0.05;
        if (disagreement > 0.35) totalPenalty += 0.10;
        rawConf -= Math.min(totalPenalty, 0.18);
        double confidence = Math.clamp(rawConf, 0.15, 1.0);

        double weightedScore = longScore > shortScore ? longScore : -shortScore;

        log.info("[Q4.judge] {} long={} short={} edge={} disagree={} domMoveBps={} avgConf={} → {} conf={}",
                horizon, String.format("%.3f", longScore), String.format("%.3f", shortScore),
                String.format("%.3f", edge), String.format("%.3f", disagreement), dominantMoveBps,
                String.format("%.2f", avgAgentConf), direction, String.format("%.2f", confidence));

        // 入场区间和止损止盈
        BigDecimal entryLow, entryHigh, invalidation, tp1, tp2;
        if (lastPrice != null && lastPrice.signum() > 0) {
            BigDecimal basisBps = BigDecimal.valueOf(Math.max(dominantVolBps, dominantMoveBps));
            BigDecimal volOffset = lastPrice.multiply(basisBps)
                    .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP);
            BigDecimal entryBuffer = volOffset.multiply(BigDecimal.valueOf(0.35)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal stopOffset = volOffset.multiply(BigDecimal.valueOf(0.95)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal tp1Offset = volOffset.multiply(BigDecimal.valueOf(1.10)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal tp2Offset = volOffset.multiply(BigDecimal.valueOf(1.85)).setScale(2, RoundingMode.HALF_UP);

            if (direction == Direction.LONG) {
                entryLow = lastPrice.subtract(entryBuffer);
                entryHigh = lastPrice.add(entryBuffer.multiply(BigDecimal.valueOf(0.4)).setScale(2, RoundingMode.HALF_UP));
                invalidation = entryLow.subtract(stopOffset);
                tp1 = entryHigh.add(tp1Offset);
                tp2 = entryHigh.add(tp2Offset);
            } else {
                entryLow = lastPrice.subtract(entryBuffer.multiply(BigDecimal.valueOf(0.4)).setScale(2, RoundingMode.HALF_UP));
                entryHigh = lastPrice.add(entryBuffer);
                invalidation = entryHigh.add(stopOffset);
                tp1 = entryLow.subtract(tp1Offset);
                tp2 = entryLow.subtract(tp2Offset);
            }
        } else {
            entryLow = entryHigh = invalidation = tp1 = tp2 = null;
        }

        int maxLeverage = getMaxLeverage(horizon, confidence, disagreement);

        return new HorizonForecast(horizon, direction, confidence, weightedScore, disagreement,
                entryLow, entryHigh, invalidation, tp1, tp2, maxLeverage,
                getBasePositionPct(horizon));
    }

    /**
     * 获取agent权重：基础权重 × 准确率修正。
     * 准确率 > 0.5 时加权，< 0.5 时减权，范围[0.5x, 1.4x]。
     * 无准确率数据时使用基础权重。
     */
    private double getWeight(String agent, String horizon) {
        double base = DEFAULT_WEIGHTS.getOrDefault(agent, Map.of())
                .getOrDefault(horizon, 0.1);
        if (agentAccuracy.isEmpty()) return base;
        Map<String, Double> horizonAcc = agentAccuracy.get(agent);
        if (horizonAcc == null) return base;
        Double acc = horizonAcc.get(horizon);
        if (acc == null) return base;
        // acc=0.5→1.0x, acc=0.8→1.24x, acc=0.3→0.68x, 范围[0.5, 1.4]
        double multiplier = Math.clamp(0.2 + acc * 1.6, 0.5, 1.4);
        return base * multiplier;
    }

    public static int getMaxLeverage(String horizon) {
        return getMaxLeverage(horizon, 0.5, 0.2);
    }

    public static int getMaxLeverage(String horizon, double confidence, double disagreement) {
        int baseLev = switch (horizon) {
            case "0_10" -> 30;
            case "10_20" -> 25;
            case "20_30" -> 20;
            default -> 20;
        };
        if (disagreement > 0.35) baseLev = Math.min(baseLev, 15);
        if (confidence < 0.4) baseLev = (int) (baseLev * 0.6);
        return Math.max(10, baseLev);
    }

    public static double getBasePositionPct(String horizon) {
        return switch (horizon) {
            case "0_10" -> 0.15;
            case "10_20" -> 0.18;
            case "20_30" -> 0.20;
            default -> 0.15;
        };
    }

    private double getMinEdge(List<String> qualityFlags) {
        double base = switch (horizon) {
            case "0_10" -> 0.12;
            case "10_20" -> 0.10;
            case "20_30" -> 0.08;
            default -> 0.12;
        };
        if (qualityFlags == null || qualityFlags.isEmpty()) {
            return base;
        }
        boolean partialData = qualityFlags.contains("PARTIAL_KLINE_DATA");
        boolean missing15m = qualityFlags.contains("MISSING_TF_15M");
        boolean missing5m = qualityFlags.contains("MISSING_TF_5M");
        if (partialData) {
            double factor = switch (horizon) {
                case "0_10" -> missing5m ? 0.75 : 0.90;
                case "10_20" -> (missing15m || missing5m) ? 0.60 : 0.85;
                case "20_30" -> missing15m ? 0.60 : 0.85;
                default -> 0.80;
            };
            return Math.max(0.05, base * factor);
        }
        return base;
    }

    private int getMinMoveBps() {
        return switch (horizon) {
            case "0_10" -> 4;
            case "10_20" -> 5;
            case "20_30" -> 6;
            default -> 5;
        };
    }

    private int calibrateMoveBps(AgentVote vote) {
        double moveFloorRatio = switch (horizon) {
            case "0_10" -> 0.60;
            case "10_20" -> 0.68;
            case "20_30" -> 0.75;
            default -> 0.65;
        };
        double confidenceFloor = Math.max(0.5, vote.confidence());
        int volatilityFloor = (int) Math.round(vote.volatilityBps() * moveFloorRatio * confidenceFloor);
        return Math.max(vote.expectedMoveBps(), volatilityFloor);
    }
}
