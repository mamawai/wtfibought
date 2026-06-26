package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuantForecastWorkflowTest {

    @Test
    void keyStrategyRegistersAllCrossNodeStateKeys() throws Exception {
        Method method = QuantForecastWorkflow.class.getDeclaredMethod("createKeyStrategyFactory");
        method.setAccessible(true);

        KeyStrategyFactory factory = (KeyStrategyFactory) method.invoke(null);
        Map<String, KeyStrategy> strategies = factory.apply();

        assertThat(strategies).containsKeys(
                "target_symbol",
                "kline_map",
                "spot_kline_map",
                "feature_snapshot",
                "macro_context",
                "indicator_map",
                "price_change_map",
                "regime_confidence",
                "regime_confidence_stddev",
                "regime_transition",
                "agent_votes",
                "filtered_news",
                "news_confidence_stddev",
                "news_low_confidence",
                "signal_panel",
                "horizon_forecasts",
                "overall_decision",
                "risk_status",
                "cycle_id",
                "fragility_score",
                "debate_summary",
                "debate_probs",
                "weak_leans",
                "report",
                "hard_report",
                "forecast_result",
                "raw_snapshot_json",
                "raw_report_json"
        );
    }
}
