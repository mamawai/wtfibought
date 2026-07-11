package com.mawai.wiibquant.agent.analysis;

import com.mawai.wiibcommon.entity.QuantNarrativeVerification;
import com.mawai.wiibcommon.entity.QuantVolVerification;
import com.mawai.wiibquant.mapper.QuantNarrativeVerificationMapper;
import com.mawai.wiibquant.mapper.QuantVolVerificationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScorecardServiceTest {

    private final QuantVolVerificationMapper mapper = mock(QuantVolVerificationMapper.class);
    private final QuantNarrativeVerificationMapper narrativeMapper = mock(QuantNarrativeVerificationMapper.class);
    private final ScorecardService service = new ScorecardService(mapper, narrativeMapper);

    private QuantVolVerification row(String horizon, double qlike, double baselineQlike, boolean hit, long closeTime) {
        QuantVolVerification r = new QuantVolVerification();
        r.setSymbol("BTCUSDT");
        r.setHorizon(horizon);
        r.setQlike(qlike);
        r.setBaselineQlike(baselineQlike);
        r.setVolStateHit(hit);
        r.setCloseTime(closeTime);
        return r;
    }

    @Test
    void aggregatesPerHorizonWithHandCheckedNumbers() {
        long now = System.currentTimeMillis();
        when(mapper.selectList(any())).thenReturn(List.of(
                row("H6", 0.2, 0.4, true, now - 3_600_000),   // 赢基准 命中
                row("H6", 0.5, 0.4, false, now - 7_200_000),  // 输基准 miss
                row("H12", 0.3, 0.6, true, now - 3_600_000)));

        ScorecardService.Scorecard card = service.scorecard("BTCUSDT", 7);

        assertThat(card.totalSamples()).isEqualTo(3);
        ScorecardService.HorizonScore h6 = card.horizons().stream()
                .filter(h -> "H6".equals(h.horizon())).findFirst().orElseThrow();
        assertThat(h6.samples()).isEqualTo(2);
        assertThat(h6.avgQlike()).isEqualTo(0.35);          // (0.2+0.5)/2
        assertThat(h6.avgBaselineQlike()).isEqualTo(0.4);
        assertThat(h6.qlikeImprovement()).isEqualTo(0.125); // (0.4-0.35)/0.4
        assertThat(h6.qlikeWinRate()).isEqualTo(0.5);
        assertThat(h6.volStateHitRate()).isEqualTo(0.5);
    }

    @Test
    void emptySamplesReportHonestNote() {
        when(mapper.selectList(any())).thenReturn(List.of());

        ScorecardService.Scorecard card = service.scorecard("BTCUSDT", 7);

        assertThat(card.totalSamples()).isZero();
        assertThat(card.note()).contains("暂无已验证样本");
    }

    @Test
    void narrativeAggregatesVerifiedOnlyAndReportsSkipped() {
        long now = System.currentTimeMillis();
        when(narrativeMapper.selectList(any())).thenReturn(List.of(
                narrativeRow(0.24, true, true, "VERIFIED", now - 3_600_000),   // 命中 且 noDirection
                narrativeRow(1.04, false, false, "VERIFIED", now - 7_200_000), // miss
                narrativeRow(null, null, false, "SKIPPED", now - 7_200_000))); // 不可对账只报数

        ScorecardService.Scorecard card = service.scorecard("BTCUSDT", 7);

        ScorecardService.NarrativeScore n = card.narrative();
        assertThat(n).isNotNull();
        assertThat(n.samples()).isEqualTo(2);
        assertThat(n.avgBrier()).isEqualTo(0.64);            // (0.24+1.04)/2
        assertThat(n.uniformBrier()).isEqualTo(0.6667);
        assertThat(n.brierImprovement()).isEqualTo(0.04);    // (2/3-0.64)/(2/3)
        assertThat(n.scenarioHitRate()).isEqualTo(0.5);
        assertThat(n.noDirectionSamples()).isEqualTo(1);
        assertThat(n.skippedSamples()).isEqualTo(1);
    }

    private QuantNarrativeVerification narrativeRow(Double brier, Boolean hit, Boolean noDirection,
                                                    String status, long closeTime) {
        QuantNarrativeVerification r = new QuantNarrativeVerification();
        r.setSymbol("BTCUSDT");
        r.setCloseTime(closeTime);
        r.setBrier(brier);
        r.setScenarioHit(hit);
        r.setNoDirection(noDirection);
        r.setStatus(status);
        return r;
    }

    @Test
    void shortRunningWindowWarnsInNote() {
        long now = System.currentTimeMillis();
        when(mapper.selectList(any())).thenReturn(List.of(
                row("H6", 0.2, 0.4, true, now - 3_600_000)));

        ScorecardService.Scorecard card = service.scorecard("BTCUSDT", 7);

        assertThat(card.runningDays()).isLessThan(7);
        assertThat(card.note()).contains("谨慎解读");
    }
}
