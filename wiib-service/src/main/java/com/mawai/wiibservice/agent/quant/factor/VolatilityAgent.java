package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class VolatilityAgent implements FactorAgent {

    @Override
    public String name() { return "volatility"; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot s) {
        Map<String, Map<String, Object>> indicators = s.indicatorsByTimeframe();
        if (indicators == null || indicators.isEmpty()) {
            return List.of(
                    AgentVote.noTrade(name(), "0_10", "NO_DATA"),
                    AgentVote.noTrade(name(), "10_20", "NO_DATA"),
                    AgentVote.noTrade(name(), "20_30", "NO_DATA"));
        }

        BigDecimal lastPrice = s.lastPrice();
        if (lastPrice == null || lastPrice.signum() <= 0) {
            return List.of(
                    AgentVote.noTrade(name(), "0_10", "NO_PRICE"),
                    AgentVote.noTrade(name(), "10_20", "NO_PRICE"),
                    AgentVote.noTrade(name(), "20_30", "NO_PRICE"));
        }

        List<String> reasons = new ArrayList<>();
        List<String> riskFlags = new ArrayList<>();

        // 波动状态分析（不产出方向）
        analyzeVolState(indicators, reasons, riskFlags);

        // ATR趋势（加速/减速）
        analyzeAtrTrend(indicators, reasons);

        // 波动率估计(基点) — 这是本agent的核心输出
        int vol0 = estimateVolBps(s, "1m", 10);    // 0-10min: 1m ATR × sqrt(10)
        int vol1 = estimateVolBps(s, "5m", 4);     // 10-20min: 5m ATR × sqrt(4)
        int vol2 = estimateVolBps(s, "5m", 6);     // 20-30min: 5m ATR × sqrt(6)

        // 波动率agent不给方向，只提供volatilityBps和风险标志
        double conf = 0.3 + Math.min(0.4, riskFlags.size() * 0.1);

        log.info("[Q3.vol] volBps[{},{},{}] reasons={} riskFlags={}",
                vol0, vol1, vol2, reasons, riskFlags);

        return List.of(
                buildVote("0_10", conf, vol0, 0.45, reasons, riskFlags),
                buildVote("10_20", conf, vol1, 0.60, reasons, riskFlags),
                buildVote("20_30", conf, vol2, 0.75, reasons, riskFlags));
    }

    private void analyzeVolState(Map<String, Map<String, Object>> indicators,
                                  List<String> reasons, List<String> riskFlags) {
        Map<String, Object> ind5m = indicators.get("5m");
        if (ind5m != null) {
            BigDecimal pb = toBd(ind5m.get("boll_pb"));
            BigDecimal bw = toBd(ind5m.get("boll_bandwidth"));

            if (pb != null) {
                double p = pb.doubleValue();
                if (p > 90) { reasons.add("BOLL_UPPER_EXTREME_5M"); riskFlags.add("PRICE_AT_UPPER_BAND"); }
                else if (p < 10) { reasons.add("BOLL_LOWER_EXTREME_5M"); riskFlags.add("PRICE_AT_LOWER_BAND"); }
            }

            if (bw != null) {
                double b = bw.doubleValue();
                if (b < 1.5) { reasons.add("BOLL_SQUEEZE_5M"); riskFlags.add("VOLATILITY_COMPRESSED"); }
                else if (b > 5.0) { reasons.add("BOLL_EXPANSION_5M"); riskFlags.add("VOLATILITY_EXPANDED"); }
            }
        }

        Map<String, Object> ind15m = indicators.get("15m");
        if (ind15m != null) {
            BigDecimal bw15 = toBd(ind15m.get("boll_bandwidth"));
            if (bw15 != null && bw15.doubleValue() < 1.2) {
                riskFlags.add("BOLL_SQUEEZE_15M");
            }
        }
    }

    private void analyzeAtrTrend(Map<String, Map<String, Object>> indicators,
                                  List<String> reasons) {
        Map<String, Object> ind1m = indicators.get("1m");
        Map<String, Object> ind5m = indicators.get("5m");

        BigDecimal atr1m = ind1m != null ? toBd(ind1m.get("atr14")) : null;
        BigDecimal atr5m = ind5m != null ? toBd(ind5m.get("atr14")) : null;

        if (atr1m != null && atr5m != null && atr5m.signum() > 0) {
            double ratio = atr1m.multiply(BigDecimal.valueOf(5))
                    .divide(atr5m, 4, RoundingMode.HALF_UP).doubleValue();
            if (ratio > 1.5) reasons.add("ATR_ACCELERATING");
            else if (ratio < 0.6) reasons.add("ATR_DECELERATING");
        }
    }

    private int estimateVolBps(FeatureSnapshot s, String timeframe, int periods) {
        BigDecimal lastPrice = s.lastPrice();
        Map<String, Map<String, Object>> indicators = s.indicatorsByTimeframe();
        if (indicators == null || lastPrice == null || lastPrice.signum() <= 0) return 30;

        Map<String, Object> ind = indicators.get(timeframe);
        if (ind == null) return 30;

        BigDecimal atr = toBd(ind.get("atr14"));
        if (atr == null) return 30;

        double scaledAtr = atr.doubleValue() * Math.sqrt(periods);
        return Math.max(5, (int) (scaledAtr / lastPrice.doubleValue() * 10000));
    }

    private AgentVote buildVote(String horizon, double conf,
                                 int volBps, double moveRatio,
                                 List<String> reasons, List<String> riskFlags) {
        // score=0: 不参与方向投票；expectedMoveBps给出该horizon下合理波动幅度
        int expectedMoveBps = (int) Math.round(volBps * moveRatio);
        return new AgentVote(name(), horizon, Direction.NO_TRADE, 0, Math.clamp(conf, 0, 1),
                expectedMoveBps, volBps, List.copyOf(reasons), List.copyOf(riskFlags));
    }

    private static BigDecimal toBd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
