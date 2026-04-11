package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class RegimeAgent implements FactorAgent {

    @Override
    public String name() { return "regime"; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot s) {
        Map<String, Map<String, Object>> indicators = s.indicatorsByTimeframe();
        if (indicators == null || indicators.isEmpty()) {
            return List.of(
                    AgentVote.noTrade(name(), "0_10", "NO_DATA"),
                    AgentVote.noTrade(name(), "10_20", "NO_DATA"),
                    AgentVote.noTrade(name(), "20_30", "NO_DATA"));
        }

        MarketRegime regime = s.regime();
        double regimeConf = s.regimeConfidence();
        String transition = s.regimeTransition();

        // 期权 IV 信号
        double dvolIndex = s.dvolIndex();
        double atmIv = s.atmIv();
        double ivSkew = s.ivSkew25d();
        boolean hasIv = dvolIndex > 0 || atmIv > 0;

        List<String> reasons = new ArrayList<>();
        reasons.add("REGIME_" + regime.name());
        if (regimeConf < 0.4) reasons.add("LOW_REGIME_CONF");

        List<String> riskFlags = new ArrayList<>();

        // 趋势方向从中周期指标提取（仅用于判断趋势方向，不做独立方向投票）
        double trendDir = extractTrendDirection(indicators);

        // 根据regime计算各区间的方向偏好和置信度
        double s0, s1, s2, conf0, conf1, conf2;

        switch (regime) {
            case TREND_UP, TREND_DOWN -> {
                double dir = regime == MarketRegime.TREND_UP ? trendDir : -Math.abs(trendDir);
                if (regime == MarketRegime.TREND_UP && trendDir < 0) dir = 0.2;
                if (regime == MarketRegime.TREND_DOWN && trendDir > 0) dir = -0.2;
                // 趋势市：中长线信号更强
                s0 = dir * 0.5;
                s1 = dir * 0.7;
                s2 = dir * 0.9;
                conf0 = 0.45 * regimeConf;
                conf1 = 0.55 * regimeConf;
                conf2 = 0.65 * regimeConf;
            }
            case RANGE -> {
                // 震荡市：弱均值回归
                s0 = trendDir * 0.2;
                s1 = trendDir * 0.15;
                s2 = trendDir * 0.1;
                conf0 = 0.35 * regimeConf;
                conf1 = 0.30 * regimeConf;
                conf2 = 0.25 * regimeConf;
                riskFlags.add("RANGE_BOUND");
            }
            case SQUEEZE -> {
                // 收缩等突破，弱方向
                s0 = trendDir * 0.3;
                s1 = trendDir * 0.45;
                s2 = trendDir * 0.6;
                conf0 = Math.min(0.40, 0.20 + Math.abs(trendDir) * 0.15) * regimeConf;
                conf1 = Math.min(0.45, 0.25 + Math.abs(trendDir) * 0.18) * regimeConf;
                conf2 = Math.min(0.50, 0.30 + Math.abs(trendDir) * 0.20) * regimeConf;
                riskFlags.add("SQUEEZE_WAIT_BREAKOUT");
            }
            case SHOCK -> {
                s0 = trendDir * 0.15;
                s1 = trendDir * 0.08;
                s2 = 0;
                conf0 = 0.15 * regimeConf;
                conf1 = 0.08 * regimeConf;
                conf2 = 0.05;
                riskFlags.add("EXTREME_VOLATILITY");
            }
            default -> {
                s0 = 0; s1 = 0; s2 = 0;
                conf0 = 0.3; conf1 = 0.3; conf2 = 0.3;
            }
        }

        // transition信号调整
        if (transition != null) {
            switch (transition) {
                case "WEAKENING" -> {
                    // 趋势衰竭：降低中长线confidence
                    conf1 *= 0.7;
                    conf2 *= 0.5;
                    riskFlags.add("TREND_WEAKENING");
                    reasons.add("TRANSITION_WEAKENING");
                }
                case "BREAKING_OUT" -> {
                    // 即将突破向上：短线升权
                    conf0 *= 1.2;
                    reasons.add("TRANSITION_BREAKING_OUT");
                }
                case "BREAKING_DOWN" -> {
                    // 即将突破向下：短线升权
                    conf0 *= 1.2;
                    reasons.add("TRANSITION_BREAKING_DOWN");
                }
                case "STRENGTHENING" -> {
                    reasons.add("TRANSITION_STRENGTHENING");
                }
            }
        }

        // IV 信号调整
        if (hasIv) {
            // skew 方向性押注: 正=call贵(看涨), 负=put贵(看空)
            if (Math.abs(ivSkew) > 2.0) {
                double skewBias = Math.clamp(ivSkew / 10.0, -0.3, 0.3);
                s0 += skewBias * 0.3;
                s1 += skewBias * 0.5;
                s2 += skewBias * 0.6;
                reasons.add(ivSkew > 0 ? "IV_SKEW_CALL_RICH" : "IV_SKEW_PUT_RICH");
            }
            // 高IV + squeeze = 聪明钱布局，提升 confidence
            double ivLevel = atmIv > 0 ? atmIv : dvolIndex;
            if (ivLevel > 60 && regime == MarketRegime.SQUEEZE) {
                conf1 *= 1.15;
                conf2 *= 1.20;
                reasons.add("HIGH_IV_SQUEEZE");
            }
            // 极高IV(>80) 在非SHOCK下 → 风险预警
            if (ivLevel > 80 && regime != MarketRegime.SHOCK) {
                riskFlags.add("ELEVATED_IMPLIED_VOL");
            }
        }

        // cap confidence to [0,1]
        conf0 = Math.clamp(conf0, 0, 1);
        conf1 = Math.clamp(conf1, 0, 1);
        conf2 = Math.clamp(conf2, 0, 1);

        int volBps = estimateVolBps(s);

        log.info("[Q3.regime] regime={} regimeConf={} transition={} trendDir={} dvol={} atmIv={} skew={} → scores[{},{},{}] confs[{},{},{}] riskFlags={}",
                regime, String.format("%.2f", regimeConf), transition, String.format("%.3f", trendDir),
                String.format("%.0f", dvolIndex), String.format("%.0f", atmIv), String.format("%.1f", ivSkew),
                String.format("%.3f", s0), String.format("%.3f", s1), String.format("%.3f", s2),
                String.format("%.2f", conf0), String.format("%.2f", conf1), String.format("%.2f", conf2),
                riskFlags);

        return List.of(
                buildVote("0_10", s0, conf0, volBps, reasons, riskFlags),
                buildVote("10_20", s1, conf1, volBps, reasons, riskFlags),
                buildVote("20_30", s2, conf2, volBps, reasons, riskFlags));
    }

    /**
     * 从中周期指标提取趋势方向，仅用于判断trend regime下的多空朝向。
     * 不做独立动量评分（那是MomentumAgent的职责）。
     */
    private double extractTrendDirection(Map<String, Map<String, Object>> indicators) {
        double dir = 0;
        // 优先15m，fallback到5m
        Map<String, Object> ind = indicators.getOrDefault("15m", indicators.get("5m"));
        if (ind != null) {
            BigDecimal plusDi = toBd(ind.get("plus_di"));
            BigDecimal minusDi = toBd(ind.get("minus_di"));
            if (plusDi != null && minusDi != null) {
                dir = Math.clamp((plusDi.doubleValue() - minusDi.doubleValue()) / 30.0, -1, 1);
            }
        }
        return dir;
    }

    private int estimateVolBps(FeatureSnapshot s) {
        BigDecimal lastPrice = s.lastPrice();
        BigDecimal atr = s.atr5m() != null ? s.atr5m() : s.atr1m();
        if (atr != null && lastPrice != null && lastPrice.signum() > 0) {
            return atr.multiply(BigDecimal.valueOf(10000))
                    .divide(lastPrice, 0, java.math.RoundingMode.HALF_UP).intValue();
        }
        return 30;
    }

    private AgentVote buildVote(String horizon, double score, double conf,
                                 int volBps, List<String> reasons, List<String> riskFlags) {
        double s = Math.clamp(score, -1, 1);
        Direction dir = Math.abs(s) < 0.05 ? Direction.NO_TRADE : (s > 0 ? Direction.LONG : Direction.SHORT);
        int moveBps = (int) (Math.abs(s) * volBps * 0.4);
        return new AgentVote(name(), horizon, dir, s, Math.clamp(conf, 0, 1), moveBps, volBps,
                List.copyOf(reasons), List.copyOf(riskFlags));
    }

    private static BigDecimal toBd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
