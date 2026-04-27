package com.mawai.wiibservice.agent.quant.observe;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Graph节点本地观测包装器：只记录耗时和异常，绝不改变节点返回值或异常传播。
 */
@Slf4j
public final class NodeObservationWrapper {

    public static final String DURATION_METRIC = "wiib.graph.node.duration";
    public static final String ERROR_METRIC = "wiib.graph.node.errors";

    private NodeObservationWrapper() {
    }

    public static NodeAction wrap(String nodeName, NodeAction delegate, MeterRegistry meterRegistry) {
        return new ObservedNodeAction(nodeName, delegate, meterRegistry);
    }

    private record ObservedNodeAction(
            String nodeName,
            NodeAction delegate,
            MeterRegistry meterRegistry
    ) implements NodeAction {

        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            long startNs = System.nanoTime();
            try {
                Map<String, Object> result = delegate.apply(state);
                long elapsedNs = System.nanoTime() - startNs;
                recordDuration("success", elapsedNs);
                log.info("[GRAPH_OBS] node={} duration={}ms status=success",
                        nodeName, elapsedMs(elapsedNs));
                return result;
            } catch (Exception e) {
                long elapsedNs = System.nanoTime() - startNs;
                recordDuration("error", elapsedNs);
                recordError();
                log.warn("[GRAPH_OBS] node={} duration={}ms status=error error={}",
                        nodeName, elapsedMs(elapsedNs), e.getMessage());
                throw e;
            }
        }

        private void recordDuration(String status, long elapsedNs) {
            if (meterRegistry == null) {
                return;
            }
            Timer.builder(DURATION_METRIC)
                    .tag("node", nodeName)
                    .tag("status", status)
                    .register(meterRegistry)
                    .record(elapsedNs, TimeUnit.NANOSECONDS);
        }

        private void recordError() {
            if (meterRegistry == null) {
                return;
            }
            Counter.builder(ERROR_METRIC)
                    .tag("node", nodeName)
                    .register(meterRegistry)
                    .increment();
        }

        private long elapsedMs(long elapsedNs) {
            return TimeUnit.NANOSECONDS.toMillis(elapsedNs);
        }
    }
}
