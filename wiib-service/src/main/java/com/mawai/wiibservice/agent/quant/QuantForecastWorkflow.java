package com.mawai.wiibservice.agent.quant;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.mawai.wiibservice.agent.quant.domain.LlmCallMode;
import com.mawai.wiibservice.agent.quant.factor.*;
import com.mawai.wiibservice.agent.quant.node.*;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.service.ForceOrderService;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 量化预测工作流：8节点线性串联。
 * <pre>
 * START → collect_data → build_features → regime_review → run_factors → run_judges → debate_judge → risk_gate → generate_report → END
 *              ↑                             ↑LLM浅            ↑              ↑          ↑LLM深                         ↑LLM浅
 *        内部并行采集                    Regime审核     内部5Agent并行   内部3Judge并行  辩论裁决                      报告生成
 * </pre>
 */
public class QuantForecastWorkflow {

    /**
     * @param deepChatClient    深模型（辩论裁决等复杂推理）
     * @param shallowChatClient 浅模型（regime审核/新闻/报告等快速任务）
     * @param binanceRestClient Binance数据接口
     * @param memoryService     记忆查询服务（可为null，无记忆时跳过注入）
     * @param deepCallMode      深模型调用策略
     * @param shallowCallMode   浅模型调用策略
     */
    public static CompiledGraph build(ChatClient.Builder deepChatClient,
                                       ChatClient.Builder shallowChatClient,
                                       BinanceRestClient binanceRestClient,
                                       MemoryService memoryService,
                                       ForceOrderService forceOrderService,
                                       LlmCallMode deepCallMode,
                                       LlmCallMode shallowCallMode) throws Exception {

        // 5个因子Agent（NewsEventAgent用浅模型）
        List<FactorAgent> agents = List.of(
                new MicrostructureAgent(),
                new MomentumAgent(),
                new RegimeAgent(),
                new VolatilityAgent(),
                new NewsEventAgent(shallowChatClient, shallowCallMode)
        );

        StateGraph workflow = new StateGraph(createKeyStrategyFactory())
                .addNode("collect_data",       node_async(new CollectDataNode(binanceRestClient, forceOrderService)))
                .addNode("build_features",     node_async(new BuildFeaturesNode()))
                .addNode("regime_review",      node_async(new RegimeReviewNode(shallowChatClient, shallowCallMode, memoryService)))
                .addNode("run_factors",        node_async(new RunFactorAgentsNode(agents)))
                .addNode("run_judges",         node_async(new RunHorizonJudgesNode()))
                .addNode("debate_judge",       node_async(new DebateJudgeNode(deepChatClient, deepCallMode, memoryService)))
                .addNode("risk_gate",          node_async(new RiskGateNode()))
                .addNode("generate_report",    node_async(new GenerateReportNode(shallowChatClient, shallowCallMode, memoryService)));

        workflow.addEdge(START, "collect_data");
        workflow.addEdge("collect_data", "build_features");
        workflow.addEdge("build_features", "regime_review");
        workflow.addEdge("regime_review", "run_factors");
        workflow.addEdge("run_factors", "run_judges");
        workflow.addEdge("run_judges", "debate_judge");
        workflow.addEdge("debate_judge", "risk_gate");
        workflow.addEdge("risk_gate", "generate_report");
        workflow.addEdge("generate_report", END);

        return workflow.compile(CompileConfig.builder().build());
    }

    private static KeyStrategyFactory createKeyStrategyFactory() {
        return () -> {
            HashMap<String, KeyStrategy> s = new HashMap<>();
            // 输入
            s.put("target_symbol", new ReplaceStrategy());
            // CollectDataNode输出
            s.put("kline_map", new ReplaceStrategy());
            s.put("ticker_map", new ReplaceStrategy());
            s.put("funding_rate_map", new ReplaceStrategy());
            s.put("funding_rate_hist_map", new ReplaceStrategy());
            s.put("orderbook_map", new ReplaceStrategy());
            s.put("open_interest_map", new ReplaceStrategy());
            s.put("oi_hist_map", new ReplaceStrategy());
            s.put("long_short_ratio_map", new ReplaceStrategy());
            s.put("force_orders_map", new ReplaceStrategy());
            s.put("top_trader_position_map", new ReplaceStrategy());
            s.put("taker_long_short_map", new ReplaceStrategy());
            s.put("fear_greed_data", new ReplaceStrategy());
            s.put("news_data", new ReplaceStrategy());
            s.put("data_available", new ReplaceStrategy());
            // BuildFeaturesNode输出
            s.put("feature_snapshot", new ReplaceStrategy());
            s.put("indicator_map", new ReplaceStrategy());
            s.put("price_change_map", new ReplaceStrategy());
            // RegimeReviewNode输出
            s.put("regime_confidence", new ReplaceStrategy());
            s.put("regime_transition", new ReplaceStrategy());
            s.put("regime_transition_detail", new ReplaceStrategy());
            // RunFactorAgentsNode输出
            s.put("agent_votes", new ReplaceStrategy());
            s.put("filtered_news", new ReplaceStrategy());
            // RunHorizonJudgesNode输出
            s.put("horizon_forecasts", new ReplaceStrategy());
            s.put("overall_decision", new ReplaceStrategy());
            s.put("risk_status", new ReplaceStrategy());
            s.put("cycle_id", new ReplaceStrategy());
            // DebateJudgeNode输出
            s.put("debate_summary", new ReplaceStrategy());
            // GenerateReportNode输出
            s.put("report", new ReplaceStrategy());
            s.put("hard_report", new ReplaceStrategy());
            s.put("forecast_result", new ReplaceStrategy());
            return s;
        };
    }
}
