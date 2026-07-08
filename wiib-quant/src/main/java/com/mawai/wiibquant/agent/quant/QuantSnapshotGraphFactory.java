package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.toolkit.QuantSnapshotService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 定时轨图工厂：进程内单例缓存。深研判节点经 QuantLlm 门面每次现取当前模型，
 * 模型热更新不需要重建图（图结构与模型解耦）。观测走框架原生三层（P8）。
 */
@Component
public class QuantSnapshotGraphFactory {

    private final QuantSnapshotService snapshotService;
    private final DeepAnalysisService deepAnalysisService;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;
    private final ObjectProvider<GraphLifecycleListener> observationListenerProvider;
    private volatile CompiledGraph cached;

    public QuantSnapshotGraphFactory(QuantSnapshotService snapshotService,
                                     DeepAnalysisService deepAnalysisService,
                                     ObjectProvider<ObservationRegistry> observationRegistryProvider,
                                     ObjectProvider<GraphLifecycleListener> observationListenerProvider) {
        this.snapshotService = snapshotService;
        this.deepAnalysisService = deepAnalysisService;
        this.observationRegistryProvider = observationRegistryProvider;
        this.observationListenerProvider = observationListenerProvider;
    }

    public CompiledGraph get() throws Exception {
        CompiledGraph graph = cached;
        if (graph != null) return graph;
        synchronized (this) {
            if (cached == null) {
                cached = QuantSnapshotWorkflow.build(snapshotService, deepAnalysisService,
                        observationRegistryProvider.getIfAvailable(),
                        observationListenerProvider.getIfAvailable());
            }
            return cached;
        }
    }
}
