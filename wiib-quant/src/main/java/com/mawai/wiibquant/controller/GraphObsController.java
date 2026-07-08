package com.mawai.wiibquant.controller;

import com.alibaba.cloud.ai.graph.observation.metric.SpringAiAlibabaObservationMetricAttributes;
import com.alibaba.cloud.ai.graph.observation.metric.SpringAiAlibabaObservationMetricNames;
import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.util.Result;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Graph 节点观测查询（P8 对齐框架原生三层观测指标；旧手搓 wrapper 指标已随旧管线拆除）。
 */
@Tag(name = "Graph 观测")
@RestController
@RequestMapping("/api/admin/graph-obs")
@RequiredArgsConstructor
@RequireAdmin // 观测端点仅管理员(userId=1)可访问
public class GraphObsController {

    /** 定时轨全节点（快照段 + 深研判段），与 QuantSnapshotWorkflow 拓扑同步 */
    private static final List<String> NODE_ORDER = List.of(
            "build_snapshot", "persist_snapshot",
            "news_context", "bull", "bear", "judge", "persist_analysis"
    );

    private final MeterRegistry meterRegistry;

    public record GraphNodeMetric(
            String node,
            long count,
            double meanMs,
            double maxMs
    ) {}

    @GetMapping("/metrics")
    @Operation(summary = "获取各节点观测指标（框架原生 graph_node 指标）")
    public Result<List<GraphNodeMetric>> metrics() {
        String metricName = SpringAiAlibabaObservationMetricNames.GRAPH_NODE.value();
        String nodeTag = SpringAiAlibabaObservationMetricAttributes.GRAPH_NODE_NAME.value();
        List<GraphNodeMetric> result = NODE_ORDER.stream().map(node -> {
            Timer timer = meterRegistry.find(metricName).tag(nodeTag, node).timer();
            long count = timer != null ? timer.count() : 0L;
            double meanMs = timer != null ? timer.mean(TimeUnit.MILLISECONDS) : 0.0;
            double maxMs = timer != null ? timer.max(TimeUnit.MILLISECONDS) : 0.0;
            return new GraphNodeMetric(node, count, meanMs, maxMs);
        }).toList();

        return Result.ok(result);
    }
}
