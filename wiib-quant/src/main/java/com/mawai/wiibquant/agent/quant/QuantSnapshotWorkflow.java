package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.toolkit.QuantSnapshotService;
import io.micrometer.observation.ObservationRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 定时轨主图（P2b 完整形态）：
 * <pre>
 * START → build_snapshot → persist_snapshot → gate 条件边
 *                                   │ 否(纯5m bar)→ END
 *                                   └ 是(1h定频/哨兵插队/手动)→ 深研判段:
 *              news_context → [bull ∥ bear](框架并行边fan-out) → judge(fan-in) → persist_analysis → END
 * </pre>
 * Bull∥Bear 用 addEdge(String, List) 原生 fan-out——框架内部走 ParallelNode，替代旧手搓虚拟线程。
 * state 只传标量与 JSON 字符串（框架 deepCopy 会破坏 record 的历史坑）；重对象走 MarketDataService 缓存共享。
 * 深研判任一 LLM 步失败只缺席本次研判（占位/null 降级），快照时序不受影响。
 * 观测走框架原生三层（graph/node/edge，P8 替换手搓 wrapper），observationRegistry/listener 可空（测试）。
 */
public class QuantSnapshotWorkflow {

    public static CompiledGraph build(QuantSnapshotService snapshotService,
                                      DeepAnalysisService deepAnalysisService,
                                      ObservationRegistry observationRegistry,
                                      GraphLifecycleListener observationListener) throws Exception {
        // ===== 快照段（零 LLM）=====
        NodeAction buildNode = state -> {
            String symbol = symbol(state);
            long closeTime = closeTime(state);
            QuantSnapshot snap = snapshotService.buildSnapshot(symbol, closeTime);
            Map<String, Object> out = new HashMap<>();
            // 快照实体过 state 用 JSON 字符串，防 deepCopy 破坏
            out.put("snapshot_json", snap != null ? JSON.toJSONString(snap) : "");
            return out;
        };
        NodeAction persistNode = state -> {
            String json = (String) state.value("snapshot_json").orElse("");
            if (json.isEmpty()) {
                return Map.of();
            }
            Long id = snapshotService.persist(JSON.parseObject(json, QuantSnapshot.class));
            return id != null ? Map.of("snapshot_id", id) : Map.of();
        };

        // ===== 深研判段（LLM，gate 后才进入）=====
        NodeAction newsContextNode = state -> Map.of(
                "news_context", deepAnalysisService.buildNewsContext(symbol(state)));
        NodeAction bullNode = state -> Map.of(
                "bull_argument", deepAnalysisService.bullArgue(symbol(state), newsContext(state)));
        NodeAction bearNode = state -> Map.of(
                "bear_argument", deepAnalysisService.bearArgue(symbol(state), newsContext(state)));
        NodeAction judgeNode = state -> {
            QuantDeepAnalysis analysis = deepAnalysisService.judge(
                    symbol(state), closeTime(state),
                    state.value("snapshot_id").filter(Number.class::isInstance)
                            .map(v -> ((Number) v).longValue()).orElse(null),
                    (String) state.value("trigger_source").orElse("unknown"),
                    newsContext(state),
                    (String) state.value("bull_argument").orElse(""),
                    (String) state.value("bear_argument").orElse(""));
            // 实体过 state 走 JSON 字符串（同快照约定）；null=本次研判缺席
            return Map.of("analysis_json", analysis != null ? JSON.toJSONString(analysis) : "");
        };
        NodeAction persistAnalysisNode = state -> {
            String json = (String) state.value("analysis_json").orElse("");
            if (json.isEmpty()) {
                return Map.of();
            }
            Long id = deepAnalysisService.persist(JSON.parseObject(json, QuantDeepAnalysis.class));
            return id != null ? Map.of("analysis_id", id) : Map.of();
        };

        StateGraph graph = new StateGraph(keyStrategies())
                .addNode("build_snapshot", node_async(buildNode))
                .addNode("persist_snapshot", node_async(persistNode))
                .addNode("news_context", node_async(newsContextNode))
                .addNode("bull", node_async(bullNode))
                .addNode("bear", node_async(bearNode))
                .addNode("judge", node_async(judgeNode))
                .addNode("persist_analysis", node_async(persistAnalysisNode));

        graph.addEdge(START, "build_snapshot");
        graph.addEdge("build_snapshot", "persist_snapshot");
        // gate：调度层判定好 trigger_deep（1h 定频/哨兵插队/手动），图内只分流——确定性门控不烧 LLM 路由
        graph.addConditionalEdges("persist_snapshot",
                edge_async(state -> Boolean.TRUE.equals(state.value("trigger_deep").orElse(false))
                        && !((String) state.value("snapshot_json").orElse("")).isEmpty()
                        ? "deep" : "end"),
                Map.of("deep", "news_context", "end", END));
        // Bull∥Bear：框架原生并行边（fan-out → fan-in），内部 ParallelNode 执行
        graph.addEdge("news_context", List.of("bull", "bear"));
        graph.addEdge(List.of("bull", "bear"), "judge");
        graph.addEdge("judge", "persist_analysis");
        graph.addEdge("persist_analysis", END);

        // 空 SaverConfig：禁用默认 MemorySaver（无容量上限会 OOM，历史教训）
        CompileConfig.Builder compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().build());
        if (observationRegistry != null && observationListener != null) {
            compileConfig.observationRegistry(observationRegistry)
                    .withLifecycleListener(observationListener);
        }
        return graph.compile(compileConfig.build());
    }

    private static KeyStrategyFactory keyStrategies() {
        return () -> {
            HashMap<String, KeyStrategy> s = new HashMap<>();
            s.put("target_symbol", new ReplaceStrategy());
            s.put("kline_close_time", new ReplaceStrategy());
            s.put("trigger_source", new ReplaceStrategy());
            s.put("trigger_deep", new ReplaceStrategy());
            s.put("snapshot_json", new ReplaceStrategy());
            s.put("snapshot_id", new ReplaceStrategy());
            s.put("news_context", new ReplaceStrategy());
            s.put("bull_argument", new ReplaceStrategy());
            s.put("bear_argument", new ReplaceStrategy());
            s.put("analysis_json", new ReplaceStrategy());
            s.put("analysis_id", new ReplaceStrategy());
            return s;
        };
    }

    private static String symbol(com.alibaba.cloud.ai.graph.OverAllState state) {
        return (String) state.value("target_symbol").orElse("BTCUSDT");
    }

    private static long closeTime(com.alibaba.cloud.ai.graph.OverAllState state) {
        return state.value("kline_close_time")
                .filter(Number.class::isInstance).map(v -> ((Number) v).longValue())
                .orElse(0L);
    }

    private static String newsContext(com.alibaba.cloud.ai.graph.OverAllState state) {
        return (String) state.value("news_context").orElse("无新闻上下文");
    }
}
