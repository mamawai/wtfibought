package com.mawai.wiibquant.agent.quant.factor;

import com.mawai.wiibquant.agent.quant.domain.*;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
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
        return evaluate(s, FactorEvaluationContext.empty());
    }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot s, FactorEvaluationContext context) {
        Map<String, Map<String, Object>> indicators = s.indicatorsByTimeframe();
        if (indicators == null || indicators.isEmpty()) {
            return List.of(
                    AgentVote.noTrade(name(), "H6", "NO_DATA"),
                    AgentVote.noTrade(name(), "H12", "NO_DATA"),
                    AgentVote.noTrade(name(), "H24", "NO_DATA"));
        }

        MarketRegime regime = s.regime();

        // 期权 IV 信号
        double dvolIndex = s.dvolIndex();
        double atmIv = s.atmIv();
        double ivSkew = s.ivSkew25d();
        boolean hasIv = dvolIndex > 0 || atmIv > 0;

        List<String> reasons = new ArrayList<>();
        reasons.add("LIVE_REGIME_" + (regime != null ? regime.name() : "UNKNOWN"));

        List<String> riskFlags = new ArrayList<>();
        if (regime == MarketRegime.RANGE) {
            riskFlags.add("RANGE_BOUND");
        } else if (regime == MarketRegime.SQUEEZE) {
            riskFlags.add("SQUEEZE_WAIT_BREAKOUT");
        } else if (regime == MarketRegime.SHOCK) {
            riskFlags.add("EXTREME_VOLATILITY");
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

        double conf = 0.5;   // 无 regime 置信来源(DebateJudge 已删)，恒中性
        double liveBias = liveRegimeBias(indicators, regime, ivSkew);

        log.info("[Q3.regime] regime={} liveBias={} dvol={} atmIv={} skew={} riskFlags={}",
                regime, String.format("%.2f", liveBias),
                String.format("%.0f", dvolIndex), String.format("%.0f", atmIv),
                String.format("%.1f", ivSkew), riskFlags);

        return List.of(
                consistencyVote("H6", ForecastHorizon.H6, conf, liveBias, reasons, riskFlags, context),
                consistencyVote("H12", ForecastHorizon.H12, conf * 0.90, liveBias, reasons, riskFlags, context),
                consistencyVote("H24", ForecastHorizon.H24, conf * 0.80, liveBias, reasons, riskFlags, context));
    }

    /**
     * 从中周期指标提取趋势方向，仅用于判断trend regime下的多空朝向。
     * 不做独立动量评分（那是MomentumAgent的职责）。
     */
    private double extractTrendDirection(Map<String, Map<String, Object>> indicators) {
        double weighted = 0;
        double weightSum = 0;
        for (TfWeight tf : List.of(new TfWeight("15m", 0.45), new TfWeight("1h", 0.35), new TfWeight("5m", 0.20))) {
            Map<String, Object> ind = indicators.get(tf.name());
            if (ind == null) continue;
            Double score = timeframeDirection(ind);
            if (score == null) continue;
            weighted += score * tf.weight();
            weightSum += tf.weight();
        }
        return weightSum > 0 ? Math.clamp(weighted / weightSum, -1, 1) : 0;
    }

    private double liveRegimeBias(Map<String, Map<String, Object>> indicators,
                                  MarketRegime regime,
                                  double ivSkew) {
        double bias = extractTrendDirection(indicators) * 0.60;
        if (regime != null) {
            bias += switch (regime) {
                case TREND_UP -> 0.35;
                case TREND_DOWN -> -0.35;
                default -> 0.0;
            };
        }
        if (Math.abs(ivSkew) > 2.0) {
            bias += ivSkew > 0 ? 0.08 : -0.08;
        }
        return Math.clamp(bias, -1, 1);
    }

    private Double timeframeDirection(Map<String, Object> ind) {
        BigDecimal plusDi = toBd(ind.get("plus_di"));
        BigDecimal minusDi = toBd(ind.get("minus_di"));
        if (plusDi != null && minusDi != null) {
            return Math.clamp((plusDi.doubleValue() - minusDi.doubleValue()) / 30.0, -1, 1);
        }
        BigDecimal ma = toBd(ind.get("ma_alignment"));
        if (ma != null) {
            return Math.clamp(ma.doubleValue() * 0.45, -1, 1);
        }
        return null;
    }

    private AgentVote riskVote(String horizon, double conf,
                               List<String> reasons, List<String> riskFlags) {
        return new AgentVote(name(), horizon, Direction.NO_TRADE, 0, Math.clamp(conf, 0, 1),
                List.copyOf(reasons), List.copyOf(riskFlags));
    }

    private AgentVote consistencyVote(String horizon, ForecastHorizon forecastHorizon,
                                      double conf, double liveBias,
                                      List<String> reasons, List<String> riskFlags,
                                      FactorEvaluationContext context) {
        MacroContext.Leg leg = context != null ? context.researchLeg(forecastHorizon) : MacroContext.Leg.neutral();
        int researchSign = leg.directionSign();
        double researchConf = leg.directionConfidence();
        if (researchSign == 0 || researchConf < 0.35 || Math.abs(liveBias) < 0.20) {
            return riskVote(horizon, conf, reasons, riskFlags);
        }

        double alignment = researchSign * liveBias;
        if (Math.abs(alignment) < 0.20) {
            return riskVote(horizon, conf, reasons, riskFlags);
        }

        double horizonScale = switch (horizon) {
            case "H6" -> 1.00;
            case "H12" -> 0.85;
            case "H24" -> 0.70;
            default -> 0.75;
        };
        double magnitude = Math.clamp(0.18 + Math.abs(liveBias) * 0.34, 0.18, 0.52) * horizonScale;
        double score = Math.copySign(magnitude, liveBias);
        double voteConf = Math.clamp(conf * Math.max(0.45, researchConf), 0.15, 0.85);

        List<String> outReasons = new ArrayList<>(reasons);
        List<String> outFlags = new ArrayList<>(riskFlags);
        if (alignment < 0) {
            outReasons.add("REGIME_VETO_AGAINST_RESEARCH");
            outFlags.add("REGIME_RESEARCH_CONFLICT");
        } else {
            outReasons.add("REGIME_SUPPORTS_RESEARCH");
        }

        Direction direction = score > 0 ? Direction.LONG : Direction.SHORT;
        return new AgentVote(name(), horizon, direction, score, voteConf,
                List.copyOf(outReasons), List.copyOf(outFlags));
    }

    private record TfWeight(String name, double weight) {}

    private static BigDecimal toBd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
