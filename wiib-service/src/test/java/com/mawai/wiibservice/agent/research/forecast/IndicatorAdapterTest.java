package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.tool.CryptoIndicatorCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndicatorAdapterTest {

    @Test
    void toCalcInputMapsHighLowCloseVolumeInOrder() {
        // KlineBar(openTime, closeTime, open, high, low, close, volume)
        KlineBar b = new KlineBar(1L, 2L, bd(10), bd(20), bd(5), bd(15), bd(99));
        List<BigDecimal[]> in = IndicatorAdapter.toCalcInput(List.of(b));
        assertThat(in.get(0)).containsExactly(bd(20), bd(5), bd(15), bd(99)); // high, low, close, volume
    }

    @Test
    void indicatorsMatchDirectCalcAllAndAreNonEmpty() {
        List<KlineBar> bars = uptrend(40);
        Map<String, Object> viaAdapter = IndicatorAdapter.indicators(bars);
        Map<String, Object> direct = CryptoIndicatorCalculator.calcAll(IndicatorAdapter.toCalcInput(bars), true);
        assertThat(viaAdapter).isEqualTo(direct);
        assertThat(viaAdapter).doesNotContainKey("error").containsKey("ma_alignment").containsKey("macd_hist");
    }

    @Test
    void researchIndicatorsMatchFullCalcForConsumedFieldsOnly() {
        List<KlineBar> bars = uptrend(120);
        Map<String, Object> light = IndicatorAdapter.researchIndicators(bars);
        Map<String, Object> full = CryptoIndicatorCalculator.calcAll(IndicatorAdapter.toCalcInput(bars), true);

        assertThat(light).doesNotContainKey("error")
                .containsKeys("ma_alignment", "rsi14", "macd_hist", "macd_hist_trend",
                        "boll_pb", "atr14", "atr_spike_ratio", "adx", "plus_di", "minus_di")
                .doesNotContainKeys("kdj_k", "obv", "volume_ratio");
        for (String key : light.keySet()) {
            assertThat(light.get(key)).as(key).isEqualTo(full.get(key));
        }
    }

    @Test
    void belowThirtyBarsReturnsEmpty() {
        assertThat(IndicatorAdapter.indicators(uptrend(29))).isEmpty();
        assertThat(IndicatorAdapter.researchIndicators(uptrend(29))).isEmpty();
        assertThat(IndicatorAdapter.indicators(List.of())).isEmpty();
        assertThat(IndicatorAdapter.indicators(null)).isEmpty();
    }

    static List<KlineBar> uptrend(int n) {
        List<KlineBar> bars = new ArrayList<>(n);
        double p = 100;
        for (int i = 0; i < n; i++) {
            long t = i * 60_000L;
            bars.add(new KlineBar(t, t + 59_999L, bd(p), bd(p + 0.5), bd(p - 0.3), bd(p + 0.2), bd(10 + i)));
            p += 0.3;
        }
        return bars;
    }

    static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
