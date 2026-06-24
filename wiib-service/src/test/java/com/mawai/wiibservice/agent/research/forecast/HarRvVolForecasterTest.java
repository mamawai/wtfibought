package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** HAR-RV 只保留 GK 输入口径：close 全平时仍要靠 OHLC 区间给出有意义的 σ。 */
class HarRvVolForecasterTest {

    @Test
    void gkModeSurvivesFlatClosesWhereCloseReturnsAreZero() {
        // close 恒 100，但高低价有真实波动区间；这是删除 close-return HAR 后必须保住的场景。
        ResearchFeatures features = ResearchFeatures.ofBars(flatCloseBars(500));

        double sigma = HarRvVolForecaster.gkDefaults(0.94, 300_000L).forecastSigma(features);

        // GK 版：极差信息照常 → per-bar σ 量级 ≈ ln(H/L)/2 ≈ 4e-3，且必须有限
        assertThat(sigma).isFinite();
        assertThat(sigma).isBetween(1e-4, 1e-1);
    }

    @Test
    void gkModeReturnsFinitePositiveSigmaOnRegularBars() {
        ResearchFeatures features = ResearchFeatures.ofBars(randomWalkBars(500));

        double sigma = HarRvVolForecaster.gkDefaults(0.94, 300_000L).forecastSigma(features);

        assertThat(sigma).isGreaterThan(0.0);
        assertThat(sigma).isFinite();
    }

    @Test
    void nameIdentifiesGkInputMode() {
        assertThat(HarRvVolForecaster.gkDefaults(0.94).name()).startsWith("har_rv_gk(");
    }

    private static List<KlineBar> flatCloseBars(int n) {
        List<KlineBar> bars = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long t = i * 300_000L;
            double hi = 100.0 * (1 + 0.004 + 0.002 * Math.sin(i / 15.0));
            double lo = 100.0 * (1 - 0.004 - 0.002 * Math.cos(i / 23.0));
            bars.add(new KlineBar(t, t + 299_999L, bd(100.0), bd(hi), bd(lo), bd(100.0), BigDecimal.ONE));
        }
        return bars;
    }

    private static List<KlineBar> randomWalkBars(int n) {
        Random rnd = new Random(11);
        List<KlineBar> bars = new ArrayList<>(n);
        double close = 30000.0;
        for (int i = 0; i < n; i++) {
            long t = i * 300_000L;
            double open = close;
            close = open * Math.exp((rnd.nextDouble() - 0.5) * 0.01);
            double hi = Math.max(open, close) * (1 + rnd.nextDouble() * 0.002);
            double lo = Math.min(open, close) * (1 - rnd.nextDouble() * 0.002);
            bars.add(new KlineBar(t, t + 299_999L, bd(open), bd(hi), bd(lo), bd(close), BigDecimal.ONE));
        }
        return bars;
    }

    private static BigDecimal bd(double v) {
        return new BigDecimal(v, new MathContext(12));
    }
}
