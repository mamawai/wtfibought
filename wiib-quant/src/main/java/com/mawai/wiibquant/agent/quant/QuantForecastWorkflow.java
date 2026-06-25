package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibquant.agent.quant.domain.LlmCallMode;
import com.mawai.wiibquant.agent.quant.factor.*;
import com.mawai.wiibquant.agent.quant.node.*;
import com.mawai.wiibquant.agent.quant.memory.MemoryService;
import com.mawai.wiibquant.agent.quant.observe.NodeObservationWrapper;
import com.mawai.wiibquant.agent.quant.service.FactorWeightOverrideService;
import com.mawai.wiibquant.agent.quant.service.MacroContextService;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibquant.config.DeribitClient;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.List;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 量化预测工作流：8节点线性串联（重构后）。
 * <pre>
 * START → collect_data → build_features → research_forecast → run_evidence_agents → consensus_judge → debate_judge → risk_gate → generate_report → END
 *              ↑                             ↑H6/H12/H24主预测     ↑Evidence辅助            ↑融合裁决        ↑LLM深裁     ↑硬风控     ↑LLM浅
 *        内部并行采集                    替代MacroContext+RegimeReview   horizon重映射→H6/H12/H24   research主票+evidence
 * </pre>
 *
 * 已移除节点：
 * - macro_context（被 ResearchForecastNode 替代）
 * - regime_review（LLM审视regime能力并入 DebateJudgeNode）
 * - run_factors（被 RunEvidenceAgentsNode 替代，输出 horizon 重映射为 H6/H12/H24）
 * - run_judges（被 ConsensusJudgeNode 替代，不再产出 entry/tp/sl/leverage）
 */
public class QuantForecastWorkflow {

    /**
     * @param deepChatClient    深模型（辩论裁决等复杂推理）
     * @param shallowChatClient 浅模型（regime审核/新闻/报告等快速任务）
     * @param binanceRestClient Binance数据接口
     * @param memoryService     记忆查询服务（可为null，无记忆时跳过注入）
     * @param deepCallMode      深模型调用策略
     * @param shallowCallMode   浅模型调用策略
     * @param decisionInterval  主决策周期：影响 BuildFeaturesNode 取哪个 timeframe 作为主决策指标
     */
    public static CompiledGraph build(ChatClient.Builder deepChatClient,
                                       ChatClient.Builder shallowChatClient,
                                       BinanceRestClient binanceRestClient,
                                       MemoryService memoryService,
                                       ForceOrderService forceOrderService,
                                       OrderFlowAggregator orderFlowAggregator,
                                       DepthStreamCache depthStreamCache,
                                       DeribitClient deribitClient,
                                       FactorWeightOverrideService weightOverrideService,
                                       MacroContextService macroContextService,
                                       LlmCallMode deepCallMode,
                                       LlmCallMode shallowCallMode,
                                       MeterRegistry meterRegistry,
                                       KlineInterval decisionInterval) throws Exception {

        // 5个日内因子Agent；6-24h research 三腿已移到 MacroContext，不再参与方向投票。
        List<FactorAgent> agents = List.of(
                new MicrostructureAgent(),
                new MomentumAgent(),
                new RegimeAgent(),
                new VolatilityAgent(),
                new NewsEventAgent(shallowChatClient, shallowCallMode)
        );

        StateGraph workflow = new StateGraph(createKeyStrategyFactory())
                .addNode("collect_data",       node_async(observed("collect_data",
                        new CollectDataNode(binanceRestClient, forceOrderService, depthStreamCache, deribitClient), meterRegistry)))
                .addNode("build_features",     node_async(observed("build_features",
                        new BuildFeaturesNode(orderFlowAggregator, decisionInterval), meterRegistry)))
                .addNode("research_forecast", node_async(observed("research_forecast",
                        new ResearchForecastNode(macroContextService), meterRegistry)))
                // regime_review 已移除——LLM审视regime能力并入 DebateJudgeNode
                .addNode("run_evidence_agents", node_async(observed("run_evidence_agents",
                        new RunEvidenceAgentsNode(agents), meterRegistry)))
                .addNode("consensus_judge",   node_async(observed("consensus_judge",
                        new ConsensusJudgeNode(memoryService, weightOverrideService), meterRegistry)))
                .addNode("debate_judge",       node_async(observed("debate_judge",
                        new DebateJudgeNode(deepChatClient, deepCallMode, memoryService), meterRegistry)))
                .addNode("risk_gate",          node_async(observed("risk_gate",
                        new RiskGateNode(), meterRegistry)))
                .addNode("generate_report",    node_async(observed("generate_report",
                        new GenerateReportNode(shallowChatClient, shallowCallMode,
                                memoryService, weightOverrideService), meterRegistry)));

        workflow.addEdge(START, "collect_data");
        workflow.addEdge("collect_data", "build_features");
        workflow.addEdge("build_features", "research_forecast");
        workflow.addEdge("research_forecast", "run_evidence_agents");
        workflow.addEdge("run_evidence_agents", "consensus_judge");
        workflow.addEdge("consensus_judge", "debate_judge");
        workflow.addEdge("debate_judge", "risk_gate");
        workflow.addEdge("risk_gate", "generate_report");
        workflow.addEdge("generate_report", END);

        // 显式传空 SaverConfig，禁用框架默认注册的 MemorySaver。
        // 否则每跑一个节点会把整份 state 深拷贝 push 进 LinkedList，3 个固定 threadId 永不清理 → OOM。
        // ⚠️ 若将来要加"工作流中断/断点恢复"能力，不能回到默认 MemorySaver（无容量上限）；
        //    需注册带 LRU/TTL 的自定义 BaseCheckpointSaver，否则会退回本次修复前的泄漏状态。
        return workflow.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().build())
                .build());
    }

    private static KeyStrategyFactory createKeyStrategyFactory() {
        return () -> {
            HashMap<String, KeyStrategy> s = new HashMap<>();
            // 输入
            s.put("target_symbol", new ReplaceStrategy());
            // CollectDataNode输出
            s.put("kline_map", new ReplaceStrategy());
            s.put("spot_kline_map", new ReplaceStrategy());
            s.put("ticker_map", new ReplaceStrategy());
            s.put("spot_ticker_map", new ReplaceStrategy());
            s.put("funding_rate_map", new ReplaceStrategy());
            s.put("funding_rate_hist_map", new ReplaceStrategy());
            s.put("orderbook_map", new ReplaceStrategy());
            s.put("spot_orderbook_map", new ReplaceStrategy());
            s.put("oi_hist_map", new ReplaceStrategy());
            s.put("long_short_ratio_map", new ReplaceStrategy());
            s.put("force_orders_map", new ReplaceStrategy());
            s.put("top_trader_position_map", new ReplaceStrategy());
            s.put("taker_long_short_map", new ReplaceStrategy());
            s.put("fear_greed_data", new ReplaceStrategy());
            s.put("news_data", new ReplaceStrategy());
            s.put("data_available", new ReplaceStrategy());
            s.put("dvol_data", new ReplaceStrategy());
            s.put("option_book_summary", new ReplaceStrategy());
            // BuildFeaturesNode输出
            s.put("feature_snapshot", new ReplaceStrategy());
            s.put("research_forecast", new ReplaceStrategy());
            s.put("macro_context", new ReplaceStrategy());
            s.put("indicator_map", new ReplaceStrategy());
            s.put("price_change_map", new ReplaceStrategy());
            // ResearchForecastNode / DebateJudgeNode 输出
            s.put("regime_confidence", new ReplaceStrategy());
            s.put("regime_confidence_stddev", new ReplaceStrategy());
            s.put("regime_transition", new ReplaceStrategy());
            s.put("regime_transition_detail", new ReplaceStrategy());
            // RunEvidenceAgentsNode输出
            s.put("agent_votes", new ReplaceStrategy());
            s.put("filtered_news", new ReplaceStrategy());
            s.put("news_confidence_stddev", new ReplaceStrategy());
            s.put("news_low_confidence", new ReplaceStrategy());
            // ConsensusJudgeNode输出
            s.put("horizon_forecasts", new ReplaceStrategy());
            s.put("consensus_forecasts", new ReplaceStrategy()); // ConsensusJudgeNode 新 key
            s.put("overall_decision", new ReplaceStrategy());
            s.put("risk_status", new ReplaceStrategy());
            s.put("cycle_id", new ReplaceStrategy());
            s.put("memory_weight_adjustments", new ReplaceStrategy());
            // DebateJudgeNode输出
            s.put("debate_summary", new ReplaceStrategy());
            s.put("debate_probs", new ReplaceStrategy());
            // GenerateReportNode输出
            s.put("report", new ReplaceStrategy());
            s.put("hard_report", new ReplaceStrategy());
            s.put("forecast_result", new ReplaceStrategy());
            s.put("raw_snapshot_json", new ReplaceStrategy());
            s.put("raw_report_json", new ReplaceStrategy());
            return s;
        };
    }

    private static NodeAction observed(String nodeName, NodeAction delegate, MeterRegistry meterRegistry) {
        return NodeObservationWrapper.wrap(nodeName, delegate, meterRegistry);
    }
}
