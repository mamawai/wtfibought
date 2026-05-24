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

    @Test
    void marksAtrFallbackWhenNoRealAtrExists() {
        QuantForecastCycle forecast = forecast("""
                {
                  "indicatorsByTimeframe": {
                    "5m": {"volume_ratio": 1.2}
                  }
                }
                """);

        MarketContext ctx = MarketContext.parse(forecast, new BigDecimal("100000"), KlineInterval.M5);

        assertThat(ctx.atr).isEqualByComparingTo("300.000");
        assertThat(ctx.atrFromFallback).isTrue();
    }

    @Test
    void parsesMaSlopeFieldsFromPrimaryAnd15mConfirmTimeframe() {
        QuantForecastCycle forecast = forecast("""
                {
                  "atr": 100,
                  "fundingRateExtreme": 0.7,
                  "indicatorsByTimeframe": {
                    "5m": {
                      "ma7": 101,
                      "ma25": 100,
                      "ma7_series_closed": [96,97,98,99,100,101,102,103],
                      "ma25_series_closed": [95,96,97,98,99,100,101,102],
                      "adx": 24,
                      "plus_di": 30,
                      "minus_di": 12,
                      "atr14_closed": 100,
                      "atr_mean_30_closed": 90,
                      "atr_spike_ratio": 1.1,
                      "boll_expanding_5": true
                    },
                    "15m": {
                      "ma7": 201,
                      "ma25": 200,
                      "ma7_series_closed": [196,197,198,199,200,201,202,203],
                      "ma25_series_closed": [195,196,197,198,199,200,201,202],
                      "atr14_closed": 120,
                      "ma_alignment": 1
                    },
                    "1h": {"ma_alignment": -1}
                  }
                }
                """);

        MarketContext ctx = MarketContext.parse(forecast, new BigDecimal("100000"), KlineInterval.M5);

        assertThat(ctx.decisionInterval).isEqualTo(KlineInterval.M5);
        assertThat(ctx.ma7).isEqualByComparingTo("101");
        assertThat(ctx.atrClosed).isEqualByComparingTo("100");
        assertThat(ctx.atrFromFallback).isFalse();
        assertThat(ctx.ma25SeriesClosed).hasSize(8);
        assertThat(ctx.confirmMa7).isEqualByComparingTo("201");
        assertThat(ctx.confirmAtr).isEqualByComparingTo("120");
        assertThat(ctx.adx).isEqualTo(24.0);
        assertThat(ctx.atrMean30Closed).isEqualByComparingTo("90");
        assertThat(ctx.atrSpikeRatio).isEqualTo(1.1);
        assertThat(ctx.bollExpanding5).isTrue();
        assertThat(ctx.fundingRateExtreme).isEqualTo(0.7);
    }

    @Test
    void m15Uses1hAsMaSlopeConfirmTimeframe() {
        QuantForecastCycle forecast = forecast("""
                {
                  "atr": 100,
                  "indicatorsByTimeframe": {
                    "15m": {
                      "ma7_series_closed": [1,2,3,4,5,6,7,8],
                      "ma25_series_closed": [1,2,3,4,5,6,7,8],
                      "adx": 24,
                      "atr_mean_30_closed": 90,
                      "atr_spike_ratio": 1.1
                    },
                    "1h": {
                      "ma7": 301,
                      "ma25": 300,
                      "ma7_series_closed": [296,297,298,299,300,301,302,303],
                      "ma25_series_closed": [295,296,297,298,299,300,301,302],
                      "atr14_closed": 180,
                      "ma_alignment": 1
                    }
                  }
                }
                """);

        MarketContext ctx = MarketContext.parse(forecast, new BigDecimal("100000"), KlineInterval.M15);

        assertThat(ctx.confirmMa7).isEqualByComparingTo("301");
        assertThat(ctx.confirmAtr).isEqualByComparingTo("180");
    }

    @Test
    void confirmAtrDoesNotFallbackToLiveAtr14ForMaSlope() {
        QuantForecastCycle forecast = forecast("""
                {
                  "atr": 100,
                  "indicatorsByTimeframe": {
                    "15m": {},
                    "1h": {
                      "ma7_series_closed": [296,297,298,299,300,301,302,303],
                      "ma25_series_closed": [295,296,297,298,299,300,301,302],
                      "atr14": 180,
                      "ma_alignment": 1
                    }
                  }
                }
                """);

        MarketContext ctx = MarketContext.parse(forecast, new BigDecimal("100000"), KlineInterval.M15);

        assertThat(ctx.confirmAtr).isNull();
    }

    private static QuantForecastCycle forecast(String snapshotJson) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson(snapshotJson);
        return forecast;
    }
}
