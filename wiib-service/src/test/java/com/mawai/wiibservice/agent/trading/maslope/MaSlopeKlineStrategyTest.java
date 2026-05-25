package com.mawai.wiibservice.agent.trading.maslope;

import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyCandidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaSlopeKlineStrategyTest {

    private final MaSlopeKlineStrategy strategy = new MaSlopeKlineStrategy();

    @AfterEach
    void resetSymbols() {
        com.mawai.wiibservice.agent.trading.entry.strategy.MaSlopeEntryStrategy.setEnabledSymbols(null);
    }

    @Test
    void acceptsFromKlineWindowsWithoutForecast() {
        MaSlopeKlineStrategy.Evaluation evaluation = strategy.evaluate(
                "ETHUSDT",
                KlineInterval.M5,
                trendKlines(120, "2200", "0.20"),
                trendKlines(120, "2200", "0.60"),
                trendKlines(120, "2200", "1.20"));

        assertThat(evaluation.accepted())
                .as(evaluation.rejectReason() + " "
                        + MaSlopeStateClassifier.classifyPrimary(evaluation.market()))
                .isTrue();
        EntryStrategyCandidate candidate = evaluation.candidate();
        assertThat(candidate.side()).isEqualTo("LONG");
        assertThat(candidate.reason()).contains("state=UP_ACCELERATING", "confirm=UP_ACCELERATING");
        assertThat(evaluation.market().qualityFlags).isEmpty();
        assertThat(evaluation.primaryIndicators()).containsKeys(
                "ma7_series_closed", "ma25_series_closed", "atr14_closed", "adx");
    }

    @Test
    void rejectsWhenRequiredHigherTimeframeKlinesAreMissing() {
        MaSlopeKlineStrategy.Evaluation evaluation = strategy.evaluate(
                "ETHUSDT",
                KlineInterval.M5,
                trendKlines(120, "2200", "0.20"),
                trendKlines(120, "2200", "0.60"),
                null);

        assertThat(evaluation.accepted()).isFalse();
        assertThat(evaluation.rejectReason()).contains("MASLOPE_KLINE_1H_INSUFFICIENT");
    }

    private static List<BigDecimal[]> trendKlines(int count, String start, String step) {
        BigDecimal base = new BigDecimal(start);
        BigDecimal increment = new BigDecimal(step);
        List<BigDecimal[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigDecimal curve = increment.multiply(BigDecimal.valueOf((long) i * i))
                    .divide(new BigDecimal("60"), 8, RoundingMode.HALF_UP);
            BigDecimal close = base.add(increment.multiply(BigDecimal.valueOf(i))).add(curve);
            BigDecimal high = close.add(new BigDecimal("0.10"));
            BigDecimal low = close.subtract(new BigDecimal("5.00"));
            BigDecimal volume = new BigDecimal("1000").add(BigDecimal.valueOf(i).multiply(new BigDecimal("4")));
            if (i >= count - 8) {
                volume = volume.add(BigDecimal.valueOf(i - count + 9L).multiply(new BigDecimal("90")));
            }
            rows.add(new BigDecimal[]{
                    high,
                    low,
                    close,
                    volume,
                    volume.multiply(new BigDecimal("0.55")),
                    BigDecimal.valueOf(i * 60_000L),
                    BigDecimal.valueOf(i * 60_000L + 59_999L)
            });
        }
        return rows;
    }
}
