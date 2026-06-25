package com.mawai.wiibquant.agent.research.eval;

import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HorizonPathOutcomeTest {

    @Test
    void computesRawPathBpsFromFutureHighLowClose() {
        HorizonPathOutcome outcome = HorizonPathOutcome.from(bd("100"), bd("104"), List.of(
                bar("100", "106", "98", "101"),
                bar("101", "103", "95", "104")
        ));

        assertThat(outcome.actualChangeBps()).isEqualTo(400);
        assertThat(outcome.maxUpBps()).isEqualTo(600);
        assertThat(outcome.maxDownBps()).isEqualTo(500);
        assertThat(outcome.maxFavorableBps(1)).isEqualTo(600);
        assertThat(outcome.maxAdverseBps(1)).isEqualTo(500);
        assertThat(outcome.maxFavorableBps(-1)).isEqualTo(500);
        assertThat(outcome.maxAdverseBps(-1)).isEqualTo(600);
    }

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
