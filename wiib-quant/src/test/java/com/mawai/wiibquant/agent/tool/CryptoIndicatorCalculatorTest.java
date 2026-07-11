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

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
