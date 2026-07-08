package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
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
        verify(deepAnalysisService, never()).buildNewsContext(anyString()); // 一分钱 LLM 没烧
    }

    @Test
    void approvedSessionRunsFullChainAndPersists() {
        approvalRegistry.markActive("wb-1-x");
        approvalRegistry.approve("wb-1-x");
        when(deepAnalysisService.buildNewsContext("BTCUSDT")).thenReturn("ctx");
        when(deepAnalysisService.bullArgue("BTCUSDT", "ctx")).thenReturn("bull");
        when(deepAnalysisService.bearArgue("BTCUSDT", "ctx")).thenReturn("bear");
        QuantDeepAnalysis analysis = new QuantDeepAnalysis();
        analysis.setNarrative("研判叙事");
        analysis.setScenariosJson("{\"bullPct\":40,\"rangePct\":35,\"bearPct\":25}");
        analysis.setNoDirection(false);
        analysis.setInvalidation("若X则作废");
        when(deepAnalysisService.judge(eq("BTCUSDT"), anyLong(), any(), eq("chat"),
                eq("ctx"), eq("bull"), eq("bear"))).thenReturn(analysis);

        String result = toolkit.runDeepAnalysis("BTCUSDT", null);

        assertThat(result).contains("\"status\":\"OK\"").contains("研判叙事").contains("作废");
        verify(deepAnalysisService).persist(analysis);
    }

    @Test
    void judgeFailureReportsFailedWithoutPersist() {
        approvalRegistry.markActive("wb-1-x");
        approvalRegistry.approve("wb-1-x");
        when(deepAnalysisService.buildNewsContext(anyString())).thenReturn("ctx");
        when(deepAnalysisService.bullArgue(anyString(), anyString())).thenReturn("b");
        when(deepAnalysisService.bearArgue(anyString(), anyString())).thenReturn("b");
        when(deepAnalysisService.judge(anyString(), anyLong(), any(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(null);

        String result = toolkit.runDeepAnalysis("BTCUSDT", null);

        assertThat(result).contains("FAILED");
        verify(deepAnalysisService, never()).persist(any());
    }
}
