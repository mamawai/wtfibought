package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.mapper.QuantSnapshotMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepAnalysisToolkitTest {

    private final DeepAnalysisService deepAnalysisService = mock(DeepAnalysisService.class);
    private final ApprovalRegistry approvalRegistry = new ApprovalRegistry();
    private final QuantSnapshotMapper snapshotMapper = mock(QuantSnapshotMapper.class);

    private final DeepAnalysisToolkit toolkit =
            new DeepAnalysisToolkit(deepAnalysisService, approvalRegistry, snapshotMapper);

    @Test
    void gateBlocksWithoutApprovalAndRegistersPending() {
        approvalRegistry.markActive("wb-1-x");

        String result = toolkit.runDeepAnalysis("BTCUSDT", null);

        assertThat(result).contains("PENDING_APPROVAL");
        assertThat(approvalRegistry.drainPending("wb-1-x")).isPresent(); // 确认卡素材已登记
        verify(deepAnalysisService, never()).buildNewsContext(); // 一分钱 LLM 没烧
    }

    @Test
    void approvedSessionRunsFullChainAndPersists() {
        approvalRegistry.markActive("wb-1-x");
        approvalRegistry.approve("wb-1-x");
        QuantSnapshot latest = new QuantSnapshot();
        latest.setId(7L);
        latest.setVolLegsJson("{\"H6\":{\"sigmaBps\":85}}");
        when(snapshotMapper.selectOne(any())).thenReturn(latest);
        when(deepAnalysisService.buildNewsContext()).thenReturn("ctx");
        when(deepAnalysisService.bullArgue("BTCUSDT", "ctx", "{\"H6\":{\"sigmaBps\":85}}")).thenReturn("bull");
        when(deepAnalysisService.bearArgue("BTCUSDT", "ctx", "{\"H6\":{\"sigmaBps\":85}}")).thenReturn("bear");
        QuantDeepAnalysis analysis = new QuantDeepAnalysis();
        analysis.setNarrative("研判叙事");
        analysis.setScenariosJson("{\"bullPct\":40,\"rangePct\":35,\"bearPct\":25}");
        analysis.setNoDirection(false);
        analysis.setInvalidation("若X则作废");
        // 挂靠最新快照：snapshotId 与三腿都来自同一行
        when(deepAnalysisService.judge(eq("BTCUSDT"), anyLong(), eq(7L), eq("chat"),
                eq("ctx"), eq("{\"H6\":{\"sigmaBps\":85}}"), eq("bull"), eq("bear"))).thenReturn(analysis);

        String result = toolkit.runDeepAnalysis("BTCUSDT", null);

        assertThat(result).contains("\"status\":\"OK\"").contains("研判叙事").contains("作废");
        verify(deepAnalysisService).persist(analysis);
    }

    @Test
    void judgeFailureReportsFailedWithoutPersist() {
        approvalRegistry.markActive("wb-1-x");
        approvalRegistry.approve("wb-1-x");
        when(deepAnalysisService.buildNewsContext()).thenReturn("ctx");
        // 库里无快照（mapper 默认返回 null）：volLegs 以 null 透传，用 any() 匹配
        when(deepAnalysisService.bullArgue(anyString(), anyString(), any())).thenReturn("b");
        when(deepAnalysisService.bearArgue(anyString(), anyString(), any())).thenReturn("b");
        when(deepAnalysisService.judge(anyString(), anyLong(), any(), anyString(),
                anyString(), any(), anyString(), anyString())).thenReturn(null);

        String result = toolkit.runDeepAnalysis("BTCUSDT", null);

        assertThat(result).contains("FAILED");
        verify(deepAnalysisService, never()).persist(any());
    }
}
