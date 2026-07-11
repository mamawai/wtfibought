package com.mawai.wiibquant.agent.research.eval;

import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HorizonPathOutcomeTest {

    @Test
    void summarizesOnlyTradedDirections() {
        DirectionPathSummary summary = DirectionPathSummary.from(
                List.of(1, -1, 0),
                List.of(
                        new HorizonPathOutcome(100, 300, 50),
                        new HorizonPathOutcome(-200, 20, 400),
                        new HorizonPathOutcome(500, 600, 10)
                ));

        assertThat(summary.tradedPoints()).isEqualTo(2);
        assertThat(summary.avgDirectionalChangeBps()).isCloseTo(150.0, within(1e-9));
        assertThat(summary.avgMaxFavorableBps()).isCloseTo(350.0, within(1e-9));
        assertThat(summary.avgMaxAdverseBps()).isCloseTo(35.0, within(1e-9));
    }

    private static KlineBar bar(String open, String high, String low, String close) {
        return new KlineBar(0L, 0L, bd(open), bd(high), bd(low), bd(close), BigDecimal.ONE);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
