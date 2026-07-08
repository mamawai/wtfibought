package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.agent.toolkit.QuantSnapshotService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuantSnapshotWorkflowTest {

    private final QuantSnapshotService snapshotService = mock(QuantSnapshotService.class);
    private final DeepAnalysisService deepAnalysisService = mock(DeepAnalysisService.class);

    private QuantSnapshot snap() {
        QuantSnapshot snap = new QuantSnapshot();
        snap.setSymbol("BTCUSDT");
        snap.setCloseTime(123L);
        return snap;
    }

    private CompiledGraph graph() throws Exception {
        return QuantSnapshotWorkflow.build(snapshotService, deepAnalysisService, null, null);
    }

    @Test
    void runsBuildThenPersistAndSkipsDeepWhenNotTriggered() throws Exception {
        when(snapshotService.buildSnapshot(eq("BTCUSDT"), anyLong())).thenReturn(snap());
        when(snapshotService.persist(any(QuantSnapshot.class))).thenReturn(7L);

        Optional<OverAllState> out = graph().invoke(Map.of(
                "target_symbol", "BTCUSDT", "kline_close_time", 123L, "trigger_deep", false));

        assertThat(out).isPresent();
        assertThat(out.get().value("snapshot_id")).contains(7L);
        // gate 分流：非深研判轮零 LLM
        verify(deepAnalysisService, never()).buildNewsContext(anyString());
        verify(deepAnalysisService, never()).judge(anyString(), anyLong(), any(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void skipsPersistWhenBuildReturnsNull() throws Exception {
        when(snapshotService.buildSnapshot(eq("BTCUSDT"), anyLong())).thenReturn(null);

        Optional<OverAllState> out = graph().invoke(Map.of(
                "target_symbol", "BTCUSDT", "kline_close_time", 123L, "trigger_deep", true));

        assertThat(out).isPresent();
        assertThat(out.get().value("snapshot_id")).isEmpty();
        verify(snapshotService, never()).persist(any());
        // 快照缺席时即使 trigger_deep=true 也不进深研判（gate 双条件）
        verify(deepAnalysisService, never()).buildNewsContext(anyString());
    }

    @Test
    void runsFullDeepChainWhenTriggered() throws Exception {
        when(snapshotService.buildSnapshot(eq("BTCUSDT"), anyLong())).thenReturn(snap());
        when(snapshotService.persist(any(QuantSnapshot.class))).thenReturn(7L);
        when(deepAnalysisService.buildNewsContext("BTCUSDT")).thenReturn("新闻上下文X");
        when(deepAnalysisService.bullArgue("BTCUSDT", "新闻上下文X")).thenReturn("bull论据");
        when(deepAnalysisService.bearArgue("BTCUSDT", "新闻上下文X")).thenReturn("bear论据");
        QuantDeepAnalysis analysis = new QuantDeepAnalysis();
        analysis.setSymbol("BTCUSDT");
        when(deepAnalysisService.judge(eq("BTCUSDT"), eq(123L), eq(7L), eq("cron_1h"),
                eq("新闻上下文X"), eq("bull论据"), eq("bear论据"))).thenReturn(analysis);
        when(deepAnalysisService.persist(any(QuantDeepAnalysis.class))).thenReturn(88L);

        Optional<OverAllState> out = graph().invoke(Map.of(
                "target_symbol", "BTCUSDT", "kline_close_time", 123L,
                "trigger_source", "cron_1h", "trigger_deep", true));

        assertThat(out).isPresent();
        assertThat(out.get().value("snapshot_id")).contains(7L);
        assertThat(out.get().value("analysis_id")).contains(88L);
        // Bull/Bear 并行边都执行过（fan-out），Judge 收到双方论据（fan-in）
        verify(deepAnalysisService).bullArgue("BTCUSDT", "新闻上下文X");
        verify(deepAnalysisService).bearArgue("BTCUSDT", "新闻上下文X");
    }

    @Test
    void deepChainAbsentWhenJudgeFails() throws Exception {
        when(snapshotService.buildSnapshot(eq("BTCUSDT"), anyLong())).thenReturn(snap());
        when(snapshotService.persist(any(QuantSnapshot.class))).thenReturn(7L);
        when(deepAnalysisService.buildNewsContext(anyString())).thenReturn("ctx");
        when(deepAnalysisService.bullArgue(anyString(), anyString())).thenReturn("b1");
        when(deepAnalysisService.bearArgue(anyString(), anyString())).thenReturn("b2");
        when(deepAnalysisService.judge(anyString(), anyLong(), any(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(null); // LLM 失败研判缺席

        Optional<OverAllState> out = graph().invoke(Map.of(
                "target_symbol", "BTCUSDT", "kline_close_time", 123L,
                "trigger_source", "sentinel", "trigger_deep", true));

        assertThat(out).isPresent();
        assertThat(out.get().value("snapshot_id")).contains(7L); // 快照不受影响
        assertThat(out.get().value("analysis_id")).isEmpty();
        verify(deepAnalysisService, never()).persist(any());
    }
}
