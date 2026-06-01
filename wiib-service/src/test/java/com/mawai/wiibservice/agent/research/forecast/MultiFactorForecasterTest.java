package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiFactorForecasterTest {

    private final MultiFactorForecaster fc = MultiFactorForecaster.defaults();

    @Test
    void allThreeLegsBullishGivesLong() {
        // 上涨(ma_alignment=+1) + 负资金费(拥挤偏空被反向→偏多) + 极恐(F&G低→偏多)
        ResearchFeatures feat = new ResearchFeatures(uptrend(40), -0.001, 10);
        assertThat(fc.forecast(feat).direction()).isEqualTo(1);
    }

    @Test
    void allThreeLegsBearishGivesShort() {
        ResearchFeatures feat = new ResearchFeatures(downtrend(40), 0.001, 90);
        assertThat(fc.forecast(feat).direction()).isEqualTo(-1);
    }

    @Test
    void offChainCanOverrideTrend() {
        // 趋势看多(ma=+1)，但极端正资金费 + 极贪 两条 off-chain 强烈偏空 → 合成转为偏空（证明 off-chain 真参与合成）
        ResearchFeatures feat = new ResearchFeatures(uptrend(40), 0.002, 95);
        assertThat(fc.forecast(feat).direction()).isEqualTo(-1);
    }

    @Test
    void warmupBelowThirtyBarsGivesFlat() {
        ResearchFeatures feat = new ResearchFeatures(uptrend(20), -0.001, 10);
        Forecast f = fc.forecast(feat);
        assertThat(f.direction()).isZero();
        assertThat(f.confidence()).isZero();
    }

    static List<KlineBar> uptrend(int n) {
        return trend(n, 0.3);
    }

    static List<KlineBar> downtrend(int n) {
        return trend(n, -0.3);
    }

    static List<KlineBar> trend(int n, double step) {
        List<KlineBar> bars = new ArrayList<>(n);
        double p = 200;
        for (int i = 0; i < n; i++) {
            long t = i * 60_000L;
            BigDecimal o = BigDecimal.valueOf(p);
            BigDecimal c = BigDecimal.valueOf(p + step);
            BigDecimal hi = BigDecimal.valueOf(Math.max(p, p + step) + 0.5);
            BigDecimal lo = BigDecimal.valueOf(Math.min(p, p + step) - 0.5);
            bars.add(new KlineBar(t, t + 59_999L, o, hi, lo, c, BigDecimal.valueOf(10 + i)));
            p += step;
        }
        return bars;
    }
}
