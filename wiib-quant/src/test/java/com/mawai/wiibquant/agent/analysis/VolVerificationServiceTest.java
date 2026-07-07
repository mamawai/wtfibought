package com.mawai.wiibquant.agent.analysis;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibcommon.entity.QuantVolVerification;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.agent.research.metrics.VolForecastScore;
import com.mawai.wiibquant.mapper.QuantSnapshotMapper;
import com.mawai.wiibquant.mapper.QuantVolVerificationMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

class VolVerificationServiceTest {

    private final QuantSnapshotMapper snapshotMapper = mock(QuantSnapshotMapper.class);
    private final QuantVolVerificationMapper verificationMapper = mock(QuantVolVerificationMapper.class);
    private final KlineHistoryStore historyStore = mock(KlineHistoryStore.class);

    private final VolVerificationService service =
            new VolVerificationService(snapshotMapper, verificationMapper, historyStore);

    private static final long T = 1_800_000_000_000L;
    private static final long H6 = ForecastHorizon.H6.millis();

    private QuantSnapshot snapWithLeg(int sigmaBps, double lowCut, double highCut) {
        JSONObject leg = new JSONObject();
        leg.put("sigmaBps", sigmaBps);
        leg.put("volState", "MID");
        leg.put("lowCut", lowCut);
        leg.put("highCut", highCut);
        JSONObject legs = new JSONObject();
        legs.put("H6", leg);
        QuantSnapshot snap = new QuantSnapshot();
        snap.setId(9L);
        snap.setSymbol("BTCUSDT");
        snap.setCloseTime(T);
        snap.setVolLegsJson(legs.toJSONString());
        return snap;
    }

    private static KlineBar bar(long closeTime, double close) {
        BigDecimal p = BigDecimal.valueOf(close);
        return new KlineBar(closeTime - 300_000 + 1, closeTime, p, p, p, p, BigDecimal.ONE);
    }

    @Test
    void verifyOneMatchesResearchQlikeFormula() {
        // 已知价格：t-h=60000, t=60600(基准|r|=ln(60600/60000)), t+h=61206(realized=ln(61206/60600))
        Map<Long, KlineBar> bars = new HashMap<>();
        bars.put(T - H6, bar(T - H6, 60000));
        bars.put(T, bar(T, 60600));
        bars.put(T + H6, bar(T + H6, 61206));

        QuantVolVerification row = service.verifyOne(snapWithLeg(100, 0.005, 0.02), ForecastHorizon.H6, bars);

        assertThat(row).isNotNull();
        double realized = Math.log(61206.0 / 60600.0);
        double forecastSigma = 100 / 10_000.0;
        // 口径对齐：与 research VolForecastScore 直接手调一致
        double expectedQlike = VolForecastScore.qlikeLosses(
                new double[]{forecastSigma}, new double[]{realized})[0];
        assertThat(row.getQlike()).isCloseTo(expectedQlike, within(1e-12));
        assertThat(row.getRealizedReturnBps()).isEqualTo((int) Math.round(realized * 10_000));
        double baseline = Math.abs(Math.log(60600.0 / 60000.0));
        assertThat(row.getBaselineSigmaBps()).isEqualTo((int) Math.round(baseline * 10_000));
        // |realized|≈0.00995 在 (0.005, 0.02) 之间 → MID，命中预测
        assertThat(row.getVolStateActual()).isEqualTo("MID");
        assertThat(row.getVolStateHit()).isTrue();
    }

    @Test
    void volStateClassifiedByStoredPitCutsNotRecomputed() {
        Map<Long, KlineBar> bars = new HashMap<>();
        bars.put(T - H6, bar(T - H6, 60000));
        bars.put(T, bar(T, 60600));
        bars.put(T + H6, bar(T + H6, 63000)); // realized ≈ 3.9% > highCut=0.02 → HIGH

        QuantVolVerification row = service.verifyOne(snapWithLeg(100, 0.005, 0.02), ForecastHorizon.H6, bars);

        assertThat(row).isNotNull();
        assertThat(row.getVolStateActual()).isEqualTo("HIGH");
        assertThat(row.getVolStateHit()).isFalse(); // 预测 MID 实际 HIGH → miss 如实记录
    }

    @Test
    void klineGapDropsPointWithoutBlocking() {
        Map<Long, KlineBar> bars = new HashMap<>();
        bars.put(T, bar(T, 60600)); // 缺 t-h 和 t+h

        assertThat(service.verifyOne(snapWithLeg(100, 0.005, 0.02), ForecastHorizon.H6, bars)).isNull();
    }

    @Test
    void missingLegReturnsNull() {
        QuantSnapshot snap = snapWithLeg(100, 0.005, 0.02);
        // H12 腿不存在
        assertThat(service.verifyOne(snap, ForecastHorizon.H12, Map.of())).isNull();
    }
}
