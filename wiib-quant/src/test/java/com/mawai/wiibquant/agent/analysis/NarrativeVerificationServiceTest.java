package com.mawai.wiibquant.agent.analysis;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.entity.QuantNarrativeVerification;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.mapper.QuantDeepAnalysisMapper;
import com.mawai.wiibquant.mapper.QuantNarrativeVerificationMapper;
import com.mawai.wiibquant.mapper.QuantSnapshotMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NarrativeVerificationServiceTest {

    private final QuantDeepAnalysisMapper analysisMapper = mock(QuantDeepAnalysisMapper.class);
    private final QuantNarrativeVerificationMapper verificationMapper = mock(QuantNarrativeVerificationMapper.class);
    private final QuantSnapshotMapper snapshotMapper = mock(QuantSnapshotMapper.class);
    private final KlineHistoryStore historyStore = mock(KlineHistoryStore.class);

    private final NarrativeVerificationService service = new NarrativeVerificationService(
            analysisMapper, verificationMapper, snapshotMapper, historyStore);

    private static final long T = 1_800_000_000_000L;
    private static final long H12 = ForecastHorizon.H12.millis();

    private QuantDeepAnalysis analysis(long closeTime, Long snapshotId) {
        QuantDeepAnalysis a = new QuantDeepAnalysis();
        a.setId(77L);
        a.setSymbol("BTCUSDT");
        a.setCloseTime(closeTime);
        a.setSnapshotId(snapshotId);
        a.setScenariosJson("{\"bullPct\":20,\"rangePct\":60,\"bearPct\":20}");
        a.setNoDirection(false);
        return a;
    }

    private void mockSnapshotWithLowCut(double lowCut) {
        JSONObject leg = new JSONObject();
        leg.put("sigmaBps", 80);
        leg.put("lowCut", lowCut);
        leg.put("highCut", lowCut * 2);
        JSONObject legs = new JSONObject();
        legs.put("H12", leg);
        QuantSnapshot snap = new QuantSnapshot();
        snap.setId(9L);
        snap.setVolLegsJson(legs.toJSONString());
        when(snapshotMapper.selectById(9L)).thenReturn(snap);
    }

    private static KlineBar bar(long closeTime, double close) {
        BigDecimal p = BigDecimal.valueOf(close);
        return new KlineBar(closeTime - 300_000 + 1, closeTime, p, p, p, p, BigDecimal.ONE);
    }

    private void mockBars(KlineBar... bars) {
        when(historyStore.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(bars));
    }

    @Test
    void directionalMoveBeyondCutIsBullWithHandCheckedBrier() {
        mockSnapshotWithLowCut(0.006);
        mockBars(bar(T, 60000), bar(T + H12, 61000)); // r=ln(61000/60000)=0.01653 > 0.006 → BULL

        QuantNarrativeVerification row = service.verifyOne(analysis(T, 9L), T + H12 + 1000);

        assertThat(row).isNotNull();
        assertThat(row.getStatus()).isEqualTo(NarrativeVerificationService.STATUS_VERIFIED);
        assertThat(row.getActualScenario()).isEqualTo("BULL");
        assertThat(row.getRealizedReturnBps()).isEqualTo(165);
        assertThat(row.getRangeCutBps()).isEqualTo(60);
        assertThat(row.getPredictedScenario()).isEqualTo("RANGE"); // argmax=60% range
        assertThat(row.getScenarioHit()).isFalse();
        // brier = (0.2-1)² + 0.6² + 0.2² = 1.04
        assertThat(row.getBrier()).isCloseTo(1.04, within(1e-9));
    }

    @Test
    void smallMoveWithinCutIsRangeHit() {
        mockSnapshotWithLowCut(0.006);
        mockBars(bar(T, 60000), bar(T + H12, 60050)); // r≈0.00083 < 0.006 → RANGE

        QuantNarrativeVerification row = service.verifyOne(analysis(T, 9L), T + H12 + 1000);

        assertThat(row).isNotNull();
        assertThat(row.getActualScenario()).isEqualTo("RANGE");
        assertThat(row.getScenarioHit()).isTrue();
        // brier = 0.2² + (0.6-1)² + 0.2² = 0.24
        assertThat(row.getBrier()).isCloseTo(0.24, within(1e-9));
    }

    @Test
    void chatWallClockAnchorFloorsToBarGrid() {
        mockSnapshotWithLowCut(0.006);
        mockBars(bar(T, 60000), bar(T + H12, 61000));

        // chat 轨 closeTime 是墙钟（不在 bar 网格上）：地板对齐到 T 后结论与网格锚一致
        QuantNarrativeVerification row = service.verifyOne(analysis(T + 123_456, 9L), T + H12 + 3_600_000);

        assertThat(row).isNotNull();
        assertThat(row.getActualScenario()).isEqualTo("BULL");
        assertThat(row.getRealizedReturnBps()).isEqualTo(165);
    }

    @Test
    void missingSnapshotCutsSkipsPermanently() {
        // snapshotId=null（如空库时的 chat 研判）：档界不可得，落 SKIPPED 不再重试
        QuantNarrativeVerification row = service.verifyOne(analysis(T, null), T + H12 + 1000);

        assertThat(row).isNotNull();
        assertThat(row.getStatus()).isEqualTo(NarrativeVerificationService.STATUS_SKIPPED);
        assertThat(row.getBrier()).isNull();
        assertThat(row.getBullPct()).isEqualTo(20); // 情景冗余存档便于审计
    }

    @Test
    void missingKlineWithinGraceRetriesLater() {
        mockSnapshotWithLowCut(0.006);
        mockBars(); // K线未就绪

        assertThat(service.verifyOne(analysis(T, 9L), T + H12 + 1000)).isNull(); // 下轮自愈
    }

    @Test
    void missingKlineBeyondGraceGivesUpAsSkipped() {
        mockSnapshotWithLowCut(0.006);
        mockBars();

        QuantNarrativeVerification row = service.verifyOne(analysis(T, 9L), T + H12 + 25 * 3_600_000L);

        assertThat(row).isNotNull();
        assertThat(row.getStatus()).isEqualTo(NarrativeVerificationService.STATUS_SKIPPED);
    }

    @Test
    void verifyDueWritesVerifiedRows() {
        mockSnapshotWithLowCut(0.006);
        mockBars(bar(T, 60000), bar(T + H12, 61000));
        when(analysisMapper.selectDueUnverified(anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(analysis(T, 9L)));

        assertThat(service.verifyDue()).isEqualTo(1);
    }
}
