package com.mawai.wiibquant.agent.quant.factor;

import com.mawai.wiibquant.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibquant.agent.quant.util.IndicatorValues.toBd;

/**
 * 波动率因子 Agent。
 * 不参与多空方向投票，只产布林带挤压/扩张、触轨、ATR 加速等形态风险标志（进面板与研判 prompt）；
 * 波动幅度的数字口径统一走 research 三腿（快照落库、记分卡对账那份），本 agent 不再自估。
 */
@Slf4j
public class VolatilityAgent implements FactorAgent {

    @Override
    public String name() { return "volatility"; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot s) {
        // indicatorsByTimeframe 由 FeatureSnapshot 紧凑构造器保证非空
        Map<String, Map<String, Object>> indicators = s.indicatorsByTimeframe();
        if (indicators.isEmpty()) {
            return List.of(
                    AgentVote.noTrade(name(), "H6", "NO_DATA"),
                    AgentVote.noTrade(name(), "H12", "NO_DATA"),
                    AgentVote.noTrade(name(), "H24", "NO_DATA"));
        }

        BigDecimal lastPrice = s.lastPrice();
        BigDecimal atr = s.atr();
        if (lastPrice == null || lastPrice.signum() <= 0) {
            return List.of(
                    AgentVote.noTrade(name(), "H6", "NO_PRICE"),
                    AgentVote.noTrade(name(), "H12", "NO_PRICE"),
                    AgentVote.noTrade(name(), "H24", "NO_PRICE"));
        }
        if (atr != null && atr.signum() == 0) {
            return List.of(
                    AgentVote.noTrade(name(), "H6", "ATR_ZERO"),
                    AgentVote.noTrade(name(), "H12", "ATR_ZERO"),
                    AgentVote.noTrade(name(), "H24", "ATR_ZERO"));
        }

        List<String> reasons = new ArrayList<>();
        List<String> riskFlags = new ArrayList<>();

        // 波动状态分析（不产出方向）
        analyzeVolState(indicators, reasons, riskFlags);

        // ATR趋势（加速/减速）
        analyzeAtrTrend(indicators, reasons);

        // 波动率agent不给方向，只提供形态风险标志
        double conf = 0.3 + Math.min(0.4, riskFlags.size() * 0.1);

        log.info("[Q3.vol] reasons={} riskFlags={}", reasons, riskFlags);

        return List.of(
                buildVote("H6", conf, reasons, riskFlags),
                buildVote("H12", conf, reasons, riskFlags),
                buildVote("H24", conf, reasons, riskFlags));
    }

    private void analyzeVolState(Map<String, Map<String, Object>> indicators,
                                  List<String> reasons, List<String> riskFlags) {
        // 固定 5m 布林带状态，描述短窗拥挤/扩张，不随主决策周期切换。
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
        // 固定 1m vs 5m 比较，用来判断短波动加速/减速。
        Map<String, Object> ind5m = indicators.get("5m");

        BigDecimal atr1m = ind1m != null ? toBd(ind1m.get("atr14")) : null;
        BigDecimal atr = ind5m != null ? toBd(ind5m.get("atr14")) : null;

        if (atr1m != null && atr != null && atr.signum() > 0) {
            double ratio = atr1m.multiply(BigDecimal.valueOf(5))
                    .divide(atr, 4, RoundingMode.HALF_UP).doubleValue();
            if (ratio > 1.5) reasons.add("ATR_ACCELERATING");
            else if (ratio < 0.6) reasons.add("ATR_DECELERATING");
        }
    }

    private AgentVote buildVote(String horizon, double conf,
                                 List<String> reasons, List<String> riskFlags) {
        // score=0: 不参与方向投票，票的价值在 reasons/riskFlags（经面板白名单进研判语料）
        return new AgentVote(name(), horizon, Direction.NO_TRADE, 0, Math.clamp(conf, 0, 1),
                List.copyOf(reasons), List.copyOf(riskFlags));
    }

}
