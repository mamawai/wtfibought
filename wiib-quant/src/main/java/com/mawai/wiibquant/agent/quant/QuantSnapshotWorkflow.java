package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibquant.agent.quant.observe.NodeObservationWrapper;
import com.mawai.wiibquant.agent.toolkit.QuantSnapshotService;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 数值快照图（定时轨骨架）：build_snapshot → persist_snapshot → END。
 * P2a 是 2 节点薄骨架，P2b 在 persist 后挂 gate 条件边接深研判段（1h 定频/哨兵插队时续跑 LLM）。
 * state 只传标量与 JSON 字符串——框架 deepCopy 会破坏 record（历史坑），重对象走 MarketDataService 缓存共享。
 */
public class QuantSnapshotWorkflow {

    public static CompiledGraph build(QuantSnapshotService service, MeterRegistry meterRegistry) throws Exception {
        NodeAction buildNode = state -> {
            String symbol = (String) state.value("target_symbol").orElse("BTCUSDT");
            long closeTime = state.value("kline_close_time")
                    .filter(Number.class::isInstance).map(v -> ((Number) v).longValue())
                    .orElse(0L);
            QuantSnapshot snap = service.buildSnapshot(symbol, closeTime);
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
            Long id = service.persist(JSON.parseObject(json, QuantSnapshot.class));
            return id != null ? Map.of("snapshot_id", id) : Map.of();
        };

        StateGraph graph = new StateGraph(keyStrategies())
                .addNode("build_snapshot", node_async(
                        NodeObservationWrapper.wrap("build_snapshot", buildNode, meterRegistry)))
                .addNode("persist_snapshot", node_async(
                        NodeObservationWrapper.wrap("persist_snapshot", persistNode, meterRegistry)));
        graph.addEdge(START, "build_snapshot");
        graph.addEdge("build_snapshot", "persist_snapshot");
        graph.addEdge("persist_snapshot", END);

        // 空 SaverConfig：禁用默认 MemorySaver（无容量上限会 OOM，历史教训，同旧图处理）
        return graph.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().build()).build());
    }

    private static KeyStrategyFactory keyStrategies() {
        return () -> {
            HashMap<String, KeyStrategy> s = new HashMap<>();
            s.put("target_symbol", new ReplaceStrategy());
            s.put("kline_close_time", new ReplaceStrategy());
            s.put("trigger_source", new ReplaceStrategy()); // P2b gate 条件边用
            s.put("snapshot_json", new ReplaceStrategy());
            s.put("snapshot_id", new ReplaceStrategy());
            return s;
        };
    }
}
