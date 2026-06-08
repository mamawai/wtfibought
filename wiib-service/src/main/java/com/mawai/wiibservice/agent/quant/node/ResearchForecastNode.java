package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibservice.agent.quant.domain.MacroContext;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import com.mawai.wiibservice.agent.quant.service.MacroContextService;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主预测节点：替代旧 macro_context 旁路角色，提升为 workflow 主链路一等输出。
 *
 * <p>每 5m 通过 {@link MacroContextService} 获取 H6/H12/H24 三腿预测（vol/regime/direction），
 * 写入 state key {@code research_forecast} 供下游 ConsensusJudge / DebateJudge / RiskGate / Trading 消费。
 * 与旧 {@code macro_context} key 并行存在，过渡期两 key 都写，下游逐步切换到新 key。</p>
 *
 * <p>本节点按本轮 5m closeTime 同步计算，不再复用 30min TTL 旧缓存。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ResearchForecastNode implements NodeAction {

    private final MacroContextService macroContextService;

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String symbol = (String) state.value("target_symbol").orElse("BTCUSDT");
        long closeTime = state.value("kline_close_time")
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::longValue)
                .orElse(System.currentTimeMillis());
        MacroContext forecast = macroContextService.computeNow(symbol, closeTime);

        log.info("[Q3] research_forecast symbol={} stale={} flags={} risk={}",
                symbol, forecast.stale(), forecast.qualityFlags(), forecast.toRiskHint());

        Map<String, Object> result = new HashMap<>();
        result.put("research_forecast", forecast);
        result.put("macro_context", forecast); // 兼容旧报告/持久化字段
        result.put("regime_confidence_stddev", 0.0);
        result.put("regime_transition", "NONE");
        result.put("regime_transition_detail", "research_baseline");

        MacroContext.Leg h6 = forecast.legs().getOrDefault(ForecastHorizon.H6, MacroContext.Leg.neutral());
        result.put("regime_confidence", h6.regimeConfidence());

        FeatureSnapshot snapshot = (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        if (snapshot != null) {
            List<String> flags = new ArrayList<>(snapshot.qualityFlags() != null ? snapshot.qualityFlags() : List.of());
            for (String flag : forecast.qualityFlags()) {
                if (!flags.contains(flag)) {
                    flags.add(flag);
                }
            }
            result.put("feature_snapshot", snapshot.withRegimeReview(
                    mapResearchRegime(h6.regime()), List.copyOf(flags),
                    h6.regimeConfidence(), "NONE"));
        }
        return result;
    }

    private static MarketRegime mapResearchRegime(
            com.mawai.wiibservice.agent.research.forecast.MarketRegime regime) {
        if (regime == null) {
            return MarketRegime.RANGE;
        }
        return switch (regime) {
            case TRENDING_UP -> MarketRegime.TREND_UP;
            case TRENDING_DOWN -> MarketRegime.TREND_DOWN;
            case RANGING -> MarketRegime.RANGE;
            case SHOCK -> MarketRegime.SHOCK;
        };
    }
}
