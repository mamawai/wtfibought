package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EwmaMomentumForecasterTest {

    private final EwmaMomentumForecaster fc = new EwmaMomentumForecaster(3, 9);

    @Test
    void risingTrendGivesLong() {
        Forecast f = fc.forecast(feat(100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110));
        assertThat(f.direction()).isEqualTo(1);
    }

    @Test
    void fallingTrendGivesShort() {
        Forecast f = fc.forecast(feat(110, 109, 108, 107, 106, 105, 104, 103, 102, 101, 100));
        assertThat(f.direction()).isEqualTo(-1);
    }

    @Test
    void insufficientHistoryGivesFlat() {
        Forecast f = fc.forecast(feat(100, 101, 102)); // < slow(9)
        assertThat(f.direction()).isZero();
        assertThat(f.confidence()).isZero();
    }

    /** 把收盘价序列包成 ResearchFeatures（链下中性，基线忽略）。 */
    static ResearchFeatures feat(double... cs) {
        List<KlineBar> bars = new ArrayList<>();
        for (double c : cs) {
            BigDecimal v = BigDecimal.valueOf(c);
            bars.add(new KlineBar(0, 0, v, v, v, v, BigDecimal.ONE));
        }
        return ResearchFeatures.ofBars(bars);
    }
}
