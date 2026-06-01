package com.mawai.wiibservice.agent.research.series;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeriesAlignerTest {

    private static final BigDecimal NEUTRAL = BigDecimal.valueOf(50);

    /** 升序序列：t=100→10, t=200→20, t=300→30。 */
    private static List<MarketSeriesPoint> series() {
        return List.of(
                new MarketSeriesPoint(100L, BigDecimal.valueOf(10)),
                new MarketSeriesPoint(200L, BigDecimal.valueOf(20)),
                new MarketSeriesPoint(300L, BigDecimal.valueOf(30)));
    }

    @Test
    void exactMatchTakesThatPoint() {
        // 决策点正好落在某点 ts：该时刻已发布，算"已知"，ts == 决策点合法
        assertThat(SeriesAligner.asOf(series(), 200L, NEUTRAL)).isEqualByComparingTo("20");
    }

    @Test
    void betweenPointsTakesEarlierNoFutureLeak() {
        // 决策点落在 200~300 之间：必须取 200（过去），绝不取 300（未来）
        assertThat(SeriesAligner.asOf(series(), 250L, NEUTRAL)).isEqualByComparingTo("20");
    }

    @Test
    void oneMillisBeforeAPointDoesNotTakeIt() {
        // 决策点 = 某点 ts − 1：该点尚未发布，取更早的 100
        assertThat(SeriesAligner.asOf(series(), 199L, NEUTRAL)).isEqualByComparingTo("10");
    }

    @Test
    void afterLastPointTakesLatestKnown() {
        // 决策点晚于末点：取最新已知值 30
        assertThat(SeriesAligner.asOf(series(), 999L, NEUTRAL)).isEqualByComparingTo("30");
    }

    @Test
    void beforeFirstPointReturnsNeutral() {
        // 决策点早于首点：无任何已发布数据 → 中性默认
        assertThat(SeriesAligner.asOf(series(), 50L, NEUTRAL)).isEqualByComparingTo("50");
    }

    @Test
    void emptyOrNullSeriesReturnsNeutral() {
        assertThat(SeriesAligner.asOf(List.of(), 200L, NEUTRAL)).isEqualByComparingTo("50");
        assertThat(SeriesAligner.asOf(null, 200L, NEUTRAL)).isEqualByComparingTo("50");
    }

    @Test
    void singlePointSeries() {
        List<MarketSeriesPoint> one = List.of(new MarketSeriesPoint(100L, BigDecimal.valueOf(7)));
        assertThat(SeriesAligner.asOf(one, 100L, NEUTRAL)).isEqualByComparingTo("7");   // ==
        assertThat(SeriesAligner.asOf(one, 150L, NEUTRAL)).isEqualByComparingTo("7");   // 之后
        assertThat(SeriesAligner.asOf(one, 99L, NEUTRAL)).isEqualByComparingTo("50");   // 之前 → 中性
    }
}
