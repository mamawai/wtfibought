package com.mawai.wiibservice.agent.trading.runtime;

import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.enums.KlineInterval;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MarketContextTest {

    @Test
    void parsesClosedVolumeRatioSeriesAndSkipsNulls() {
        QuantForecastCycle forecast = forecast("""
                {
                  "atr": 100,
                  "indicatorsByTimeframe": {
                    "5m": {
                      "volume_ratio": 1.2,
                      "volume_ratio_recent_5_closed": [0.7, null, 0.9],
                      "close_trend_recent_3_closed": "falling_3"
                    }
                  }
                }
                """);

        MarketContext ctx = MarketContext.parse(forecast, new BigDecimal("100000"), KlineInterval.M5);

        assertThat(ctx.volumeRatioClosedSeries).containsExactly(0.7, 0.9);
        assertThat(ctx.closeTrendClosed3).isEqualTo("falling_3");
    }

    @Test
    void oldSnapshotDefaultsClosedVolumeRatioSeriesToEmptyList() {
        QuantForecastCycle forecast = forecast("""
                {
                  "atr": 100,
                  "indicatorsByTimeframe": {
                    "5m": {"volume_ratio": 1.2}
                  }
                }
                """);

        MarketContext ctx = MarketContext.parse(forecast, new BigDecimal("100000"), KlineInterval.M5);

        assertThat(ctx.volumeRatioClosedSeries).isEmpty();
        assertThat(ctx.closeTrendClosed3).isNull();
    }

    private static QuantForecastCycle forecast(String snapshotJson) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson(snapshotJson);
        return forecast;
    }
}
