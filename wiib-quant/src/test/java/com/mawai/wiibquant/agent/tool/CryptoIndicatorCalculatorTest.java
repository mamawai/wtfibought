package com.mawai.wiibquant.agent.tool;

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
    void maSeriesClosedExcludesLastKline() {
        List<BigDecimal> closes = IntStream.rangeClosed(1, 31)
                .mapToObj(BigDecimal::valueOf)
                .toList();

        List<BigDecimal> series = CryptoIndicatorCalculator.maSeriesClosed(closes, 3, 2);

        assertThat(series).containsExactly(bd("28.00000000"), bd("29.00000000"));
    }

    @Test
    void maSeriesClosedIncludesLastKlineWhenBacktestBarIsClosed() {
        List<BigDecimal> closes = IntStream.rangeClosed(1, 31)
                .mapToObj(BigDecimal::valueOf)
                .toList();

        List<BigDecimal> series = CryptoIndicatorCalculator.maSeriesClosed(closes, 3, 2, true);

        assertThat(series).containsExactly(bd("29.00000000"), bd("30.00000000"));
    }

    @Test
    void atrMeanAndSpikeRatioRequireEnoughClosedAtrValues() {
        List<BigDecimal[]> klines = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            BigDecimal close = bd("100").add(BigDecimal.valueOf(i));
            klines.add(new BigDecimal[]{
                    close.add(bd("2")),
                    close.subtract(bd("2")),
                    close,
                    bd("100")
            });
        }

        Map<String, Object> result = CryptoIndicatorCalculator.calcAll(klines);

        assertThat(result).containsKeys("atr_series_closed", "atr14_closed", "atr_mean_30_closed", "atr_spike_ratio");
    }

    @Test
    void atrMeanForSpikeExcludesCurrentClosedAtr() {
        List<BigDecimal> atrSeries = IntStream.rangeClosed(1, 31)
                .mapToObj(BigDecimal::valueOf)
                .toList();

        BigDecimal mean = CryptoIndicatorCalculator.tailMeanExcludingLast(atrSeries, 30);

        assertThat(mean).isEqualByComparingTo("15.50000000");
    }

    @Test
    void seventyTwoBarsExposeMaSlopeConfirmFields() {
        List<BigDecimal[]> klines = new ArrayList<>();
        for (int i = 1; i <= 72; i++) {
            BigDecimal close = bd("100").add(BigDecimal.valueOf(i));
            klines.add(new BigDecimal[]{
                    close.add(bd("2")),
                    close.subtract(bd("2")),
                    close,
                    bd("100")
            });
        }

        Map<String, Object> result = CryptoIndicatorCalculator.calcAll(klines);

        assertThat((List<?>) result.get("ma7_series_closed")).hasSize(12);
        assertThat((List<?>) result.get("ma25_series_closed")).hasSize(12);
        assertThat(result).containsKeys("atr_series_closed", "atr14_closed", "atr_mean_30_closed", "atr_spike_ratio", "adx");
    }

    @Test
    void calcAllExposesLastClosedBarKeyWhenKlineTimeExists() {
        List<BigDecimal[]> klines = new ArrayList<>();
        for (int i = 1; i <= 31; i++) {
            BigDecimal close = bd("100").add(BigDecimal.valueOf(i));
            klines.add(new BigDecimal[]{
                    close.add(bd("2")),
                    close.subtract(bd("2")),
                    close,
                    bd("100"),
                    bd("50"),
                    BigDecimal.valueOf(1_000L + i),
                    BigDecimal.valueOf(2_000L + i)
            });
        }

        Map<String, Object> result = CryptoIndicatorCalculator.calcAll(klines);

        assertThat(result.get("last_closed_bar_key")).isEqualTo("2030");
    }

    @Test
    void bollExpandingUsesClosedBandwidthWindow() {
        List<BigDecimal> closes = new ArrayList<>();
        for (int i = 0; i < 25; i++) closes.add(bd("100"));
        closes.addAll(List.of(bd("96"), bd("104"), bd("94"), bd("106"), bd("92"), bd("108"), bd("1000")));

        List<BigDecimal> bandwidth = CryptoIndicatorCalculator.bollBandwidthSeriesClosed(closes, 20, 2, 6);

        assertThat(bandwidth).hasSize(6);
        assertThat(bandwidth.getLast()).isGreaterThan(bandwidth.getFirst());
    }

    @Test
    void closedTrendSummaryExcludesLastKline() {
        String trend = CryptoIndicatorCalculator.closedTrendSummary(
                List.of(bd("100"), bd("99"), bd("98"), bd("97"), bd("110")), 3);

        assertThat(trend).isEqualTo("falling_3");
    }

    @Test
    void closedTrendSummaryIncludesLastWhenBacktestBarIsClosed() {
        String trend = CryptoIndicatorCalculator.closedTrendSummary(
                List.of(bd("100"), bd("99"), bd("98"), bd("97"), bd("110")), 3, true);

        assertThat(trend).isEqualTo("mostly_down");
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
