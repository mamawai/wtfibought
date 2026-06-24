package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.eval.VolTruthProxyDiagnostic.TruthBlock;
import com.mawai.wiibservice.agent.research.eval.VolTruthProxyDiagnostic.VolTruthProxyReport;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class VolTruthProxyDiagnosticTest {

    @Test
    void runProducesThreeTruthBlocksAndTruthChoiceChangesQlike() {
        List<KlineBar> bars = syntheticBars(500);
        // 小参数让 walk-forward 在 500 根内产生 OOS 点：testSize=3, minTrain=8, embargo=1
        EvalParams params = new EvalParams(1.5, 0.94, 3, 1, 8, 10, 0.95, 42L);

        VolTruthProxyReport r = VolTruthProxyDiagnostic.run("BTCUSDT", ForecastHorizon.H6, bars, params);

        assertThat(r.oosPoints()).isGreaterThan(0);
        assertThat(r.truths()).extracting(TruthBlock::truth)
                .containsExactly("singleR2", "pathRvClose", "pathRvGk");
        for (TruthBlock b : r.truths()) {
            assertThat(b.qlikeByForecaster()).containsKeys(
                    "har_gk", "ewma", "climatology", "lag1_ewma");
            assertThat(b.dm()).hasSize(5);
        }
        // 同一预测(har_gk)在不同真值下 QLIKE 必不同 → 证明"换真值"确实改变了测量本身
        double singleR2 = r.truths().get(0).qlikeByForecaster().get("har_gk");
        double pathClose = r.truths().get(1).qlikeByForecaster().get("har_gk");
        assertThat(singleR2).isNotNaN();
        assertThat(pathClose).isNotNaN();
        assertThat(Math.abs(singleR2 - pathClose)).isGreaterThan(1e-9);
    }

    static List<KlineBar> syntheticBars(int n) {
        Random rnd = new Random(7); // 固定种子 → 可复现
        List<KlineBar> bars = new ArrayList<>(n);
        double close = 30000.0;
        long t = 0;
        for (int i = 0; i < n; i++) {
            double open = close;
            close = open * Math.exp((rnd.nextDouble() - 0.5) * 0.01);     // ±0.5% 漂移
            double hi = Math.max(open, close) * (1 + rnd.nextDouble() * 0.002); // 真实高低 → GK 非平凡
            double lo = Math.min(open, close) * (1 - rnd.nextDouble() * 0.002);
            bars.add(new KlineBar(t, t + 300_000L - 1, bd(open), bd(hi), bd(lo), bd(close), BigDecimal.ONE));
            t += 300_000L;
        }
        return bars;
    }

    static BigDecimal bd(double v) {
        return new BigDecimal(v, new MathContext(12));
    }
}
