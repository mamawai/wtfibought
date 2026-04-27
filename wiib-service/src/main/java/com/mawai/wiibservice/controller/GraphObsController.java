package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.quant.observe.NodeObservationWrapper;
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

@Tag(name = "Graph 观测")
@RestController
@RequestMapping("/api/admin/graph-obs")
@RequiredArgsConstructor
public class GraphObsController {

    private static final List<String> NODE_ORDER = List.of(
            "collect_data", "build_features", "regime_review",
            "run_factors", "run_judges", "debate_judge",
            "risk_gate", "generate_report"
    );

    private final MeterRegistry meterRegistry;

    public record GraphNodeMetric(
            String node,
            long successCount,
            long errorCount,
            double meanMs,
            double maxMs
    ) {}

    @GetMapping("/metrics")
    @Operation(summary = "获取各节点观测指标")
    public Result<List<GraphNodeMetric>> metrics() {
        StpUtil.checkLogin();
        if (StpUtil.getLoginIdAsLong() != 1L) throw new RuntimeException("无权限");

        List<GraphNodeMetric> result = NODE_ORDER.stream().map(node -> {
            Timer successTimer = meterRegistry.find(NodeObservationWrapper.DURATION_METRIC)
                    .tag("node", node).tag("status", "success").timer();
            Timer errorTimer = meterRegistry.find(NodeObservationWrapper.DURATION_METRIC)
                    .tag("node", node).tag("status", "error").timer();

            long successCount = successTimer != null ? successTimer.count() : 0L;
            long errorCount = errorTimer != null ? errorTimer.count() : 0L;
            double meanMs = successTimer != null ? successTimer.mean(TimeUnit.MILLISECONDS) : 0.0;
            double maxMs = successTimer != null ? successTimer.max(TimeUnit.MILLISECONDS) : 0.0;

            return new GraphNodeMetric(node, successCount, errorCount, meanMs, maxMs);
        }).toList();

        return Result.ok(result);
    }
}
