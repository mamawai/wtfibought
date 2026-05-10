package com.mawai.wiibservice.agent.tool;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoIndicatorCalculatorTest {

    @Test
    void closedVolumeRatioSeriesReturnsRecentClosedRatios() {
        List<BigDecimal> volumes = IntStream.rangeClosed(1, 26)
                .mapToObj(BigDecimal::valueOf)
                .toList();

        List<BigDecimal> series = CryptoIndicatorCalculator.closedVolumeRatioSeries(volumes, 3, 5);

        assertThat(series).containsExactly(
                bd("1.05"), bd("1.05"), bd("1.05"), bd("1.04"), bd("1.04"));
    }

    @Test
    void closedVolumeRatioSeriesReturnsEmptyWhenClosedDataInsufficient() {
        List<BigDecimal> series = CryptoIndicatorCalculator.closedVolumeRatioSeries(
                List.of(bd("1"), bd("2"), bd("3")), 3, 5);

        assertThat(series).isEmpty();
    }

    @Test
    void closedVolumeRatioSeriesReturnsShortListWhenRecentWindowExceedsAvailableRatios() {
        List<BigDecimal> series = CryptoIndicatorCalculator.closedVolumeRatioSeries(
                List.of(bd("1"), bd("1"), bd("1"), bd("1"), bd("1"), bd("9")), 3, 5);

        assertThat(series).containsExactly(bd("1.00"), bd("1.00"), bd("1.00"));
    }

    @Test
    void closedVolumeRatioSeriesSkipsZeroAveragePoint() {
        List<BigDecimal> series = CryptoIndicatorCalculator.closedVolumeRatioSeries(
                List.of(bd("0"), bd("0"), bd("0"), bd("3"), bd("4"), bd("9")), 3, 5);

        assertThat(series).containsExactly(bd("3.00"), bd("1.71"));
    }

    @Test
    void calcAllExposesClosedVolumeRatioSeries() {
        List<BigDecimal[]> klines = new ArrayList<>();
        for (int i = 1; i <= 31; i++) {
            BigDecimal close = bd("100").add(BigDecimal.valueOf(i));
            klines.add(new BigDecimal[]{
                    close.add(BigDecimal.ONE),
                    close.subtract(BigDecimal.ONE),
                    close,
                    BigDecimal.valueOf(i)
            });
        }

        Map<String, Object> result = CryptoIndicatorCalculator.calcAll(klines);

        assertThat(result).containsKey("volume_ratio_recent_5_closed");
        assertThat((List<?>) result.get("volume_ratio_recent_5_closed")).hasSize(5);
        assertThat(result).containsKey("close_trend_recent_3_closed");
    }

    @Test
    void closedTrendSummaryExcludesLastKline() {
        String trend = CryptoIndicatorCalculator.closedTrendSummary(
                List.of(bd("100"), bd("99"), bd("98"), bd("97"), bd("110")), 3);

        assertThat(trend).isEqualTo("falling_3");
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
