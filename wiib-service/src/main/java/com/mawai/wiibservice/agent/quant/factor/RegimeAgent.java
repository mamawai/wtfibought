package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 市场状态因子 Agent。
 * 不重复 research 主 regime，只把当前 live regime/IV/transition 做一致性和风险 evidence。
 */
@Slf4j
public class RegimeAgent implements FactorAgent {

    @Override
    public String name() { return "regime"; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot s) {
        Map<String, Map<String, Object>> indicators = s.indicatorsByTimeframe();
        if (indicators == null || indicators.isEmpty()) {
            return List.of(
                    AgentVote.noTrade(name(), "H6", "NO_DATA"),
                    AgentVote.noTrade(name(), "H12", "NO_DATA"),
                    AgentVote.noTrade(name(), "H24", "NO_DATA"));
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
        reasons.add("LIVE_REGIME_" + (regime != null ? regime.name() : "UNKNOWN"));
        if (regimeConf < 0.4) reasons.add("LOW_REGIME_CONF");

        List<String> riskFlags = new ArrayList<>();
        if (regime == MarketRegime.RANGE) {
            riskFlags.add("RANGE_BOUND");
        } else if (regime == MarketRegime.SQUEEZE) {
            riskFlags.add("SQUEEZE_WAIT_BREAKOUT");
        } else if (regime == MarketRegime.SHOCK) {
            riskFlags.add("EXTREME_VOLATILITY");
        }

        // transition信号调整
        if (transition != null) {
            switch (transition) {
                case "WEAKENING" -> {
                    riskFlags.add("TREND_WEAKENING");
                    reasons.add("TRANSITION_WEAKENING");
                }
                case "BREAKING_OUT" -> {
                    reasons.add("TRANSITION_BREAKING_OUT");
                }
                case "BREAKING_DOWN" -> {
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
                reasons.add(ivSkew > 0 ? "IV_SKEW_CALL_RICH" : "IV_SKEW_PUT_RICH");
            }
            double ivLevel = atmIv > 0 ? atmIv : dvolIndex;
            if (ivLevel > 60 && regime == MarketRegime.SQUEEZE) {
                reasons.add("HIGH_IV_SQUEEZE");
            }
            if (ivLevel > 80 && regime != MarketRegime.SHOCK) {
                riskFlags.add("ELEVATED_IMPLIED_VOL");
            }
        }

        int volBps = estimateVolBps(s);
        double conf = Math.clamp(Math.max(0.15, regimeConf), 0.0, 1.0);

        log.info("[Q3.regime] regime={} regimeConf={} transition={} dvol={} atmIv={} skew={} riskFlags={}",
                regime, String.format("%.2f", regimeConf), transition,
                String.format("%.0f", dvolIndex), String.format("%.0f", atmIv),
                String.format("%.1f", ivSkew), riskFlags);

        return List.of(
                riskVote("H6", conf, volBps, reasons, riskFlags),
                riskVote("H12", conf * 0.90, volBps, reasons, riskFlags),
                riskVote("H24", conf * 0.80, volBps, reasons, riskFlags));
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
        BigDecimal atr = s.atr() != null ? s.atr() : s.atr1m();
        if (atr != null && lastPrice != null && lastPrice.signum() > 0) {
            return atr.multiply(BigDecimal.valueOf(10000))
                    .divide(lastPrice, 0, java.math.RoundingMode.HALF_UP).intValue();
        }
        return 30;
    }

    private AgentVote riskVote(String horizon, double conf,
                               int volBps, List<String> reasons, List<String> riskFlags) {
        return new AgentVote(name(), horizon, Direction.NO_TRADE, 0, Math.clamp(conf, 0, 1), 0, volBps,
                List.copyOf(reasons), List.copyOf(riskFlags));
    }

    private static BigDecimal toBd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
