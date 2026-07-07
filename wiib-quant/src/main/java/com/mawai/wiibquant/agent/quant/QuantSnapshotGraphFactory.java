package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mawai.wiibquant.agent.toolkit.QuantSnapshotService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 快照图工厂：零 LLM 无模型依赖，进程内单例缓存（不需要旧 RuntimeManager 的 per-model 双缓存）。 */
@Component
public class QuantSnapshotGraphFactory {

    private final QuantSnapshotService snapshotService;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private volatile CompiledGraph cached;

    public QuantSnapshotGraphFactory(QuantSnapshotService snapshotService,
                                     ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.snapshotService = snapshotService;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    public CompiledGraph get() throws Exception {
        CompiledGraph graph = cached;
        if (graph != null) return graph;
        synchronized (this) {
            if (cached == null) {
                cached = QuantSnapshotWorkflow.build(snapshotService, meterRegistryProvider.getIfAvailable());
            }
            return cached;
        }
    }
}
