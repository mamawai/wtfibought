package com.mawai.wiibquant.market.indicator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoIndicatorCalculatorTest {

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

        Map<String, Object> result = CryptoIndicatorCalculator.calcResearchSnapshot(klines, false);

        assertThat(result).containsKeys("atr14_closed", "atr_mean_30_closed", "atr_spike_ratio");
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
    void bollBandwidthAndPercentBDoNotDependOnPriceScale() {
        List<BigDecimal[]> tiny = new ArrayList<>();
        List<BigDecimal[]> scaled = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            BigDecimal close = i % 2 == 0 ? bd("0.00001") : bd("0.00002");
            BigDecimal big = close.movePointRight(6);
            tiny.add(new BigDecimal[]{close, close, close, bd("100")});
            scaled.add(new BigDecimal[]{big, big, big, bd("100")});
        }

        Map<String, Object> a = CryptoIndicatorCalculator.calcAll(tiny);
        Map<String, Object> b = CryptoIndicatorCalculator.calcAll(scaled);

        assertThat(a.get("boll_bandwidth")).isEqualTo(b.get("boll_bandwidth"));
        assertThat(a.get("boll_pb")).isEqualTo(b.get("boll_pb"));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
