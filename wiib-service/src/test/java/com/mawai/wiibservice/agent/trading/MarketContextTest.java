package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibcommon.entity.QuantForecastCycle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MarketContextTest {

    @Test
    void parsesClosedVolumeRatioSeriesAndSkipsNulls() {
        QuantForecastCycle forecast = forecast("""
                {
                  "atr5m": 100,
                  "indicatorsByTimeframe": {
                    "5m": {
                      "volume_ratio": 1.2,
                      "volume_ratio_recent_5_closed": [0.7, null, 0.9],
                      "close_trend_recent_3_closed": "falling_3"
                    }
                  }
                }
                """);

        MarketContext ctx = MarketContext.parse(forecast, new BigDecimal("100000"));

        assertThat(ctx.volumeRatioClosedSeries5m).containsExactly(0.7, 0.9);
        assertThat(ctx.closeTrendClosed3_5m).isEqualTo("falling_3");
    }

    @Test
    void oldSnapshotDefaultsClosedVolumeRatioSeriesToEmptyList() {
        QuantForecastCycle forecast = forecast("""
                {
                  "atr5m": 100,
                  "indicatorsByTimeframe": {
                    "5m": {"volume_ratio": 1.2}
                  }
                }
                """);

        MarketContext ctx = MarketContext.parse(forecast, new BigDecimal("100000"));

        assertThat(ctx.volumeRatioClosedSeries5m).isEmpty();
        assertThat(ctx.closeTrendClosed3_5m).isNull();
    }

    private static QuantForecastCycle forecast(String snapshotJson) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson(snapshotJson);
        return forecast;
    }
}
