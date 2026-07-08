package com.mawai.wiibquant.agent.quant.observe;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.observation.GraphObservationLifecycleListener;
import com.alibaba.cloud.ai.graph.observation.edge.GraphEdgeObservationHandler;
import com.alibaba.cloud.ai.graph.observation.graph.GraphObservationHandler;
import com.alibaba.cloud.ai.graph.observation.node.GraphNodeObservationHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 框架原生三层观测（P8，替换手搓 NodeObservationWrapper）：
 * graph/node/edge 三层 ObservationHandler 注册进 ObservationRegistry → 指标经 MeterRegistry 出 Prometheus。
 * 图编译时挂 observationRegistry + GraphObservationLifecycleListener 即全链路可观测。
 */
@Configuration
public class GraphObservationConfig {

    /** 三层 handler 注册（actuator 自动配置的 ObservationRegistry + Prometheus MeterRegistry）。 */
    @Bean
    public GraphLifecycleListener graphObservationLifecycleListener(ObservationRegistry observationRegistry,
                                                                    MeterRegistry meterRegistry) {
        observationRegistry.observationConfig()
                .observationHandler(new GraphObservationHandler(meterRegistry))
                .observationHandler(new GraphNodeObservationHandler(meterRegistry))
                .observationHandler(new GraphEdgeObservationHandler(meterRegistry));
        return new GraphObservationLifecycleListener(observationRegistry);
    }
}
