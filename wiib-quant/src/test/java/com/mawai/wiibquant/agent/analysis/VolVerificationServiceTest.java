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
    private static final long BAR = KlineHistoryStore.DEFAULT_BAR_MILLIS;

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

    /** 铺满 [from, to] 的 5m 收盘序列：价格由 priceAt(closeTime) 给出。 */
    private static Map<Long, KlineBar> denseBars(long from, long to, java.util.function.LongToDoubleFunction priceAt) {
        Map<Long, KlineBar> bars = new HashMap<>();
        for (long ct = from; ct <= to; ct += BAR) {
            bars.put(ct, bar(ct, priceAt.applyAsDouble(ct)));
        }
        return bars;
    }

    /** 与实现同口径的期望基准：t 往前 3 天、5m 步进的重叠 horizon 收益 RMS（PIT：窗口终点 ≤ t）。 */
    private static double expectedRollingSigma(Map<Long, KlineBar> bars, long t, long horizonMillis) {
        double sumSq = 0;
        int n = 0;
        for (long s = t; s >= t - VolVerificationService.BASELINE_LOOKBACK_MILLIS; s -= BAR) {
            KlineBar end = bars.get(s);
            KlineBar start = bars.get(s - horizonMillis);
            if (end == null || start == null) continue;
            double r = Math.log(end.close().doubleValue() / start.close().doubleValue());
            sumSq += r * r;
            n++;
        }
        return Math.sqrt(sumSq / n);
    }

    @Test
    void verifyOneMatchesResearchQlikeFormula() {
        // 波浪价格路径：基准=前3天重叠窗收益 RMS；realized=ln(close(t+h)/close(t))
        Map<Long, KlineBar> bars = denseBars(T - VolVerificationService.BASELINE_LOOKBACK_MILLIS - H6, T + H6,
                ct -> 60000.0 * (1 + 0.01 * Math.sin(ct / (double) H6)));

        QuantVolVerification row = service.verifyOne(snapWithLeg(100, 0.005, 0.02), ForecastHorizon.H6, bars);

        assertThat(row).isNotNull();
        double realized = Math.log(bars.get(T + H6).close().doubleValue() / bars.get(T).close().doubleValue());
        double forecastSigma = 100 / 10_000.0;
        // 口径对齐：与 research VolForecastScore 直接手调一致
        double expectedQlike = VolForecastScore.qlikeLosses(
                new double[]{forecastSigma}, new double[]{realized})[0];
        assertThat(row.getQlike()).isCloseTo(expectedQlike, within(1e-12));
        assertThat(row.getRealizedReturnBps()).isEqualTo((int) Math.round(realized * 10_000));
        double baseline = expectedRollingSigma(bars, T, H6);
        assertThat(row.getBaselineSigmaBps()).isEqualTo((int) Math.round(baseline * 10_000));
        double expectedBaselineQlike = VolForecastScore.qlikeLosses(
                new double[]{baseline}, new double[]{realized})[0];
        assertThat(row.getBaselineQlike()).isCloseTo(expectedBaselineQlike, within(1e-12));
    }

    @Test
    void quietPrevWindowNoLongerExplodesBaselineQlike() {
        // 复现 202693 bug 场景：上一个 H6 单窗收平（旧口径 σ=0 → QLIKE 爆到 1e7），
        // 3 天窗内其余时段有正常波动 → 新口径 RMS 正常，QLIKE 回到个位数量级
        Map<Long, KlineBar> bars = denseBars(T - VolVerificationService.BASELINE_LOOKBACK_MILLIS - H6, T + H6, ct -> {
            if (ct >= T - H6 && ct <= T) return 60000.0;                  // 安静单窗：端到端收益=0
            return 60000.0 * (1 + 0.008 * Math.sin(ct / (double) H6));   // 其余正常波动
        });
        // 未来实际动了 1%：旧口径下 x/h = 1e-4/1e-12 = 1e8
        long tPlus = T + H6;
        bars.put(tPlus, bar(tPlus, 60600));

        QuantVolVerification row = service.verifyOne(snapWithLeg(100, 0.005, 0.02), ForecastHorizon.H6, bars);

        assertThat(row).isNotNull();
        assertThat(row.getBaselineSigmaBps()).isGreaterThan(0);
        assertThat(row.getBaselineQlike()).isLessThan(50.0);
    }

    @Test
    void volStateClassifiedByStoredPitCutsNotRecomputed() {
        Map<Long, KlineBar> bars = denseBars(T - VolVerificationService.BASELINE_LOOKBACK_MILLIS - H6, T + H6,
                ct -> 60000.0 * (1 + 0.005 * Math.sin(ct / (double) H6)));
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
        bars.put(T, bar(T, 60600)); // 缺 t+h 与基准窗

        assertThat(service.verifyOne(snapWithLeg(100, 0.005, 0.02), ForecastHorizon.H6, bars)).isNull();
    }

    @Test
    void insufficientBaselineWindowsDropsPoint() {
        // 只有 t-h/t/t+h 三根：基准可用窗 =1 < 最小窗数 → 丢点，禁止退化回单窗基准
        Map<Long, KlineBar> bars = new HashMap<>();
        bars.put(T - H6, bar(T - H6, 60000));
        bars.put(T, bar(T, 60600));
        bars.put(T + H6, bar(T + H6, 61206));

        assertThat(service.verifyOne(snapWithLeg(100, 0.005, 0.02), ForecastHorizon.H6, bars)).isNull();
    }

    @Test
    void missingLegReturnsNull() {
        QuantSnapshot snap = snapWithLeg(100, 0.005, 0.02);
        // H12 腿不存在
        assertThat(service.verifyOne(snap, ForecastHorizon.H12, Map.of())).isNull();
    }
}
