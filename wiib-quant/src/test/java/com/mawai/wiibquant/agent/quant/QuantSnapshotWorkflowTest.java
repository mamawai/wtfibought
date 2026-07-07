package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibquant.agent.toolkit.QuantSnapshotService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantSnapshotWorkflowTest {

    private final QuantSnapshotService service = mock(QuantSnapshotService.class);

    @Test
    void runsBuildThenPersist() throws Exception {
        QuantSnapshot snap = new QuantSnapshot();
        snap.setSymbol("BTCUSDT");
        snap.setCloseTime(123L);
        when(service.buildSnapshot(eq("BTCUSDT"), anyLong())).thenReturn(snap);
        when(service.persist(any(QuantSnapshot.class))).thenReturn(7L);

        CompiledGraph graph = QuantSnapshotWorkflow.build(service, null);
        Optional<OverAllState> out = graph.invoke(Map.of("target_symbol", "BTCUSDT", "kline_close_time", 123L));

        assertThat(out).isPresent();
        assertThat(out.get().value("snapshot_id")).contains(7L);
    }

    @Test
    void skipsPersistWhenBuildReturnsNull() throws Exception {
        when(service.buildSnapshot(eq("BTCUSDT"), anyLong())).thenReturn(null);

        CompiledGraph graph = QuantSnapshotWorkflow.build(service, null);
        Optional<OverAllState> out = graph.invoke(Map.of("target_symbol", "BTCUSDT", "kline_close_time", 123L));

        assertThat(out).isPresent();
        assertThat(out.get().value("snapshot_id")).isEmpty();
        verify(service, never()).persist(any());
    }
}
