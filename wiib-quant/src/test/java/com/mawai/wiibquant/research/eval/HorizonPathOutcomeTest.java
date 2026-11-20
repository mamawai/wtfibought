package com.mawai.wiibquant.research.eval;

import org.junit.jupiter.api.Test;

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
}
