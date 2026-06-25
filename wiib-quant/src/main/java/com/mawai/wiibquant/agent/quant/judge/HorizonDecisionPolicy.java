package com.mawai.wiibquant.agent.quant.judge;

import com.mawai.wiibquant.agent.quant.domain.Direction;

import java.util.List;

/**
 * H6/H12/H24 三腿裁决聚合为整体 decision / riskStatus 的共享策略。
 *
 * <p>ConsensusJudge 与 DebateJudge 共用同一口径，避免双节点决策漂移：
 * H6 是主执行方向；H24 永不单独 PRIORITIZE，只能与 H12 同向时走保守 fallback。</p>
 */
public final class HorizonDecisionPolicy {

    /** H12/H24 保守 fallback 的最低置信度 */
    private static final double CONSERVATIVE_MIN_CONF = 0.58;
    private static final double HIGH_DISAGREEMENT = 0.35;
    private static final double CAUTIOUS_DISAGREEMENT = 0.25;

    private HorizonDecisionPolicy() {
    }

    /** H6 主执行；H12/H24 仅同向强背景时保守输出；H24 不单独 PRIORITIZE。 */
    public static String overallDecision(List<ConsensusForecast> forecasts) {
        if (forecasts == null || forecasts.isEmpty()) return "FLAT";
        ConsensusForecast h6 = find(forecasts, "H6");
        ConsensusForecast h12 = find(forecasts, "H12");
        ConsensusForecast h24 = find(forecasts, "H24");
        if (isDirectional(h6)) {
            return "PRIORITIZE_H6_" + h6.direction().name();
        }
        if (isDirectional(h12) && isDirectional(h24)
                && h12.direction() == h24.direction()
                && Math.min(h12.confidence(), h24.confidence()) >= CONSERVATIVE_MIN_CONF) {
            return "CONSERVATIVE_H12_" + h12.direction().name();
        }
        return "FLAT";
    }

    /** 整体风险档：全 NO_TRADE / 高分歧 / 谨慎 / 正常。 */
    public static String overallRiskStatus(List<ConsensusForecast> forecasts) {
        if (forecasts == null || forecasts.isEmpty()) return "UNKNOWN";
        long noTradeCount = forecasts.stream()
                .filter(f -> f.direction() == Direction.NO_TRADE)
                .count();
        double maxDisagreement = forecasts.stream()
                .mapToDouble(ConsensusForecast::disagreement).max().orElse(0);
        if (noTradeCount == forecasts.size()) return "ALL_NO_TRADE";
        if (maxDisagreement >= HIGH_DISAGREEMENT) return "HIGH_DISAGREEMENT";
        if (noTradeCount >= 1 || maxDisagreement >= CAUTIOUS_DISAGREEMENT) return "CAUTIOUS";
        return "NORMAL";
    }

    private static ConsensusForecast find(List<ConsensusForecast> forecasts, String horizon) {
        for (ConsensusForecast forecast : forecasts) {
            if (horizon.equals(forecast.horizon())) {
                return forecast;
            }
        }
        return null;
    }

    private static boolean isDirectional(ConsensusForecast forecast) {
        return forecast != null
                && forecast.direction() != Direction.NO_TRADE
                && forecast.confidence() > 0;
    }
}
