package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchEvalServiceTest {

    @Test
    void evaluateBarsProducesOutOfSampleReportOnUptrend() {
        // 小参数（testSize=3,minTrain=2,embargo=0,iters=200）→ 只需约 7 个 H6 bar = 7*360 个 1m bar
        EvalParams params = new EvalParams(1.5, 0.94, 3, 0, 2, 200, 0.95, 42L);
        List<KlineBar> oneMin = syntheticUptrend1m(8 * 360); // 8 个 6h 桶，含余量

        EvalReport r = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, oneMin, new EwmaMomentumForecaster(2, 4), params);

        assertThat(r).isNotNull();
        assertThat(r.symbol()).isEqualTo("BTCUSDT");
        assertThat(r.horizonHours()).isEqualTo(6);
        assertThat(r.testPoints()).isGreaterThan(0);
        assertThat(r.metrics().periods()).isEqualTo(r.testPoints());
        assertThat(r.buyAndHoldReturn().doubleValue()).isGreaterThan(0); // 上行趋势 buy&hold 为正
        assertThat(r.naivePercentile()).isBetween(0.0, 1.0);
        assertThat(r.summary()).contains("BTCUSDT");
    }

    /** 生成 count 根 1m bar，价格每根 +0.1，时间从 0 起每根 +60_000ms。 */
    static List<KlineBar> syntheticUptrend1m(int count) {
        List<KlineBar> bars = new ArrayList<>(count);
        double price = 100.0;
        for (int i = 0; i < count; i++) {
            long t = i * 60_000L;
            BigDecimal o = BigDecimal.valueOf(price);
            BigDecimal c = BigDecimal.valueOf(price + 0.1);
            BigDecimal hi = BigDecimal.valueOf(price + 0.15);
            BigDecimal lo = BigDecimal.valueOf(price - 0.05);
            bars.add(new KlineBar(t, t + 59_999L, o, hi, lo, c, BigDecimal.ONE));
            price += 0.1;
        }
        return bars;
    }
}
