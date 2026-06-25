package com.mawai.wiibquant.agent.research.forecast;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A3.1 regime 分类核心腿：ADX 方向 + ATR spike 判 SHOCK，暖机中性。 */
class RegimeClassifierTest {

    @Test
    void strongAdxPlusDiDominantIsTrendingUp() {
        RegimeVerdict v = RegimeClassifier.classify(ind(55, 25, 10, 1.0));

        assertThat(v.regime()).isEqualTo(MarketRegime.TRENDING_UP);
        assertThat(v.confidence()).isGreaterThan(0.0);
    }

    @Test
    void strongAdxMinusDiDominantIsTrendingDown() {
        RegimeVerdict v = RegimeClassifier.classify(ind(55, 10, 25, 1.0));

        assertThat(v.regime()).isEqualTo(MarketRegime.TRENDING_DOWN);
    }

    @Test
    void weakAdxIsRanging() {
        RegimeVerdict v = RegimeClassifier.classify(ind(30, 18, 16, 1.0));

        assertThat(v.regime()).isEqualTo(MarketRegime.RANGING);
    }

    @Test
    void highAtrSpikeIsShock() {
        // spike 优先：即便 adx 高、方向明确，也先判 SHOCK
        RegimeVerdict v = RegimeClassifier.classify(ind(55, 25, 10, 3.0));

        assertThat(v.regime()).isEqualTo(MarketRegime.SHOCK);
    }

    @Test
    void customLowerTrendAdxRemainsSupportedForDiagnostics() {
        RegimeVerdict v = RegimeClassifier.classify(ind(30, 25, 10, 1.0), 25.0, 2.0);

        assertThat(v.regime()).isEqualTo(MarketRegime.TRENDING_UP);
    }

    @Test
    void emptyIndicatorsIsRangingZeroConf() {
        RegimeVerdict v = RegimeClassifier.classify(Map.of());

        assertThat(v.regime()).isEqualTo(MarketRegime.RANGING);
        assertThat(v.confidence()).isZero();
    }

    private static Map<String, Object> ind(double adx, double plusDi, double minusDi, double spike) {
        return Map.of(
                "adx", BigDecimal.valueOf(adx),
                "plus_di", BigDecimal.valueOf(plusDi),
                "minus_di", BigDecimal.valueOf(minusDi),
                "atr_spike_ratio", BigDecimal.valueOf(spike));
    }
}
