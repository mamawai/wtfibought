package com.mawai.wiibservice.agent.research.metrics;

import com.mawai.wiibservice.agent.research.forecast.MarketRegime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** A2 regime 分类评分：准确率 / balanced accuracy / macro F1 / persistence 朴素基准 / 空 / 长度校验。 */
class RegimeClassificationScoreTest {

    private static final MarketRegime UP = MarketRegime.TRENDING_UP;
    private static final MarketRegime DOWN = MarketRegime.TRENDING_DOWN;
    private static final MarketRegime RANGE = MarketRegime.RANGING;
    private static final MarketRegime SHOCK = MarketRegime.SHOCK;

    @Test
    void allCorrectIsAccuracyOne() {
        MarketRegime[] same = {UP, UP, RANGE, DOWN};
        RegimeClassificationScore s = RegimeClassificationScore.evaluate(same, same);

        assertThat(s.accuracy()).isEqualTo(1.0);
        assertThat(s.balancedAccuracy()).isEqualTo(1.0);
        assertThat(s.macroF1()).isEqualTo(1.0);
        assertThat(s.n()).isEqualTo(4);
    }

    @Test
    void naiveUsesPersistence() {
        // actual=[UP,UP,UP,DOWN]：persistence 命中 i1✓ i2✓ i3✗ → 2/3
        MarketRegime[] actual = {UP, UP, UP, DOWN};
        RegimeClassificationScore s = RegimeClassificationScore.evaluate(actual, actual);

        assertThat(s.naiveAccuracy()).isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    void beatsNaiveWhenMoreAccurate() {
        MarketRegime[] actual = {UP, UP, UP, DOWN};
        RegimeClassificationScore s = RegimeClassificationScore.evaluate(actual, actual); // 完美预测 acc=1 > naive 2/3

        assertThat(s.beatsNaive()).isTrue();
    }

    @Test
    void losesToNaiveWhenLessAccurate() {
        MarketRegime[] actual = {UP, UP, UP, UP};        // 极稳 → persistence=1
        MarketRegime[] predicted = {DOWN, UP, DOWN, UP}; // acc=0.5
        RegimeClassificationScore s = RegimeClassificationScore.evaluate(predicted, actual);

        assertThat(s.accuracy()).isEqualTo(0.5);
        assertThat(s.naiveAccuracy()).isEqualTo(1.0);
        assertThat(s.beatsNaive()).isFalse();
    }

    @Test
    void balancedAccuracyExposesSkewThatPlainAccuracyHides() {
        // 极偏斜：actual 9 RANGING + 1 UP；predicted 全 RANGING。
        // accuracy=0.9 看似高；但 UP 召回=0 → balanced=(R召回1 + UP召回0)/2=0.5。
        MarketRegime[] actual = {RANGE, RANGE, RANGE, RANGE, RANGE, RANGE, RANGE, RANGE, RANGE, UP};
        MarketRegime[] predicted = {RANGE, RANGE, RANGE, RANGE, RANGE, RANGE, RANGE, RANGE, RANGE, RANGE};

        RegimeClassificationScore s = RegimeClassificationScore.evaluate(predicted, actual);

        assertThat(s.accuracy()).isCloseTo(0.9, within(1e-9));
        assertThat(s.balancedAccuracy()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void macroF1AveragesOverActualPresentClasses() {
        // actual=[UP, RANGING], predicted=[UP, UP]：UP F1=2/3，RANGING F1=0 → macroF1=1/3。
        MarketRegime[] actual = {UP, RANGE};
        MarketRegime[] predicted = {UP, UP};

        RegimeClassificationScore s = RegimeClassificationScore.evaluate(predicted, actual);

        assertThat(s.macroF1()).isCloseTo(1.0 / 3.0, within(1e-4));
    }

    @Test
    void confusionMatrixRowsAreActualAndColumnsArePredicted() {
        MarketRegime[] actual = {UP, UP, DOWN, RANGE};
        MarketRegime[] predicted = {UP, RANGE, UP, RANGE};

        RegimeClassificationScore s = RegimeClassificationScore.evaluate(predicted, actual);

        assertThat(s.confusionMatrix().get(UP).get(UP)).isEqualTo(1);
        assertThat(s.confusionMatrix().get(UP).get(RANGE)).isEqualTo(1);
        assertThat(s.confusionMatrix().get(DOWN).get(UP)).isEqualTo(1);
        assertThat(s.confusionMatrix().get(RANGE).get(RANGE)).isEqualTo(1);
        assertThat(s.confusionMatrix().get(SHOCK).values()).containsOnly(0);
    }

    @Test
    void legacyConstructorsFillZeroConfusionMatrix() {
        RegimeClassificationScore s = new RegimeClassificationScore(0.5, 0.4, true, 4);

        assertThat(s.confusionMatrix()).containsOnlyKeys(MarketRegime.values());
        assertThat(s.confusionMatrix().values())
                .allSatisfy(row -> assertThat(row).containsOnlyKeys(MarketRegime.values()));
        assertThat(s.confusionMatrix().values().stream()
                .flatMap(row -> row.values().stream())
                .mapToInt(Integer::intValue)
                .sum()).isZero();
    }

    @Test
    void emptyIsZero() {
        RegimeClassificationScore s = RegimeClassificationScore.evaluate(new MarketRegime[0], new MarketRegime[0]);

        assertThat(s.n()).isZero();
        assertThat(s.accuracy()).isZero();
        assertThat(s.balancedAccuracy()).isZero();
        assertThat(s.macroF1()).isZero();
        assertThat(s.beatsNaive()).isFalse();
        assertThat(s.confusionMatrix().values().stream()
                .flatMap(row -> row.values().stream())
                .mapToInt(Integer::intValue)
                .sum()).isZero();
    }

    @Test
    void mismatchedLengthsRejected() {
        assertThatThrownBy(() -> RegimeClassificationScore.evaluate(
                new MarketRegime[]{UP}, new MarketRegime[]{UP, DOWN}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
