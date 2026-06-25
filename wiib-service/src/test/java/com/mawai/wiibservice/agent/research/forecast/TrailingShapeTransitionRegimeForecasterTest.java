package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrailingShapeTransitionRegimeForecasterTest {

    @Test
    void learnsFutureRegimeTransitionFromTrainingWindow() {
        TrailingShapeTransitionRegimeForecaster fc = new TrailingShapeTransitionRegimeForecaster(4, 3);
        List<TrainingSample> train = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            train.add(sample(upShape(), MarketRegime.TRENDING_UP));
        }
        for (int i = 0; i < 20; i++) {
            train.add(sample(rangeShape(), MarketRegime.RANGING));
        }

        RegimeForecaster trained = fc.fit(train);
        RegimeVerdict verdict = trained.forecastRegime(ResearchFeatures.ofBars(upShape()));

        assertThat(verdict.regime()).isEqualTo(MarketRegime.TRENDING_UP);
        assertThat(verdict.confidence()).isEqualTo(1.0);
    }

    @Test
    void insufficientShapeTransitionsFallBackToRanging() {
        TrailingShapeTransitionRegimeForecaster fc = new TrailingShapeTransitionRegimeForecaster(4, 3);
        RegimeForecaster trained = fc.fit(List.of(
                sample(upShape(), MarketRegime.TRENDING_UP),
                sample(upShape(), MarketRegime.TRENDING_UP)));

        RegimeVerdict verdict = trained.forecastRegime(ResearchFeatures.ofBars(upShape()));

        assertThat(verdict.regime()).isEqualTo(MarketRegime.RANGING);
        assertThat(verdict.confidence()).isZero();
    }

    @Test
    void liftedMinorityCanBeatRangingMajority() {
        TrailingShapeTransitionRegimeForecaster fc = new TrailingShapeTransitionRegimeForecaster(4, 8);
        List<TrainingSample> train = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            train.add(sample(upShape(), MarketRegime.TRENDING_UP));
        }
        for (int i = 0; i < 10; i++) {
            train.add(sample(upShape(), MarketRegime.RANGING));
        }
        for (int i = 0; i < 50; i++) {
            train.add(sample(rangeShape(), MarketRegime.RANGING));
        }

        RegimeVerdict verdict = fc.fit(train).forecastRegime(ResearchFeatures.ofBars(upShape()));

        assertThat(verdict.regime()).isEqualTo(MarketRegime.TRENDING_UP);
        assertThat(verdict.confidence()).isEqualTo(5.0 / 15.0);
    }

    @Test
    void tieFallsBackToRanging() {
        TrailingShapeTransitionRegimeForecaster fc = new TrailingShapeTransitionRegimeForecaster(4, 2);
        RegimeForecaster trained = fc.fit(List.of(
                sample(upShape(), MarketRegime.TRENDING_UP),
                sample(upShape(), MarketRegime.RANGING),
                sample(rangeShape(), MarketRegime.RANGING),
                sample(rangeShape(), MarketRegime.RANGING)));

        RegimeVerdict verdict = trained.forecastRegime(ResearchFeatures.ofBars(upShape()));

        assertThat(verdict.regime()).isEqualTo(MarketRegime.RANGING);
    }

    private static TrainingSample sample(List<KlineBar> bars, MarketRegime actual) {
        return new TrainingSample(ResearchFeatures.ofBars(bars), BigDecimal.ZERO, 0.0, actual);
    }

    private static List<KlineBar> upShape() {
        return bars(100.0, 101.0, 102.0, 103.0);
    }

    private static List<KlineBar> rangeShape() {
        return bars(100.0, 101.0, 100.0, 101.0);
    }

    private static List<KlineBar> bars(double... closes) {
        List<KlineBar> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            BigDecimal c = BigDecimal.valueOf(closes[i]);
            out.add(new KlineBar(i, i, c, c, c, c, BigDecimal.ONE));
        }
        return out;
    }
}
