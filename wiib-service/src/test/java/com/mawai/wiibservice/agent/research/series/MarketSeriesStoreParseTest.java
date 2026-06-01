package com.mawai.wiibservice.agent.research.series;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketSeriesStoreParseTest {

    @Test
    void parsesFundingRateHistory() {
        // Binance /fapi/v1/fundingRate 原始: [{symbol, fundingTime(ms), fundingRate, markPrice}]
        String json = "[{\"symbol\":\"BTCUSDT\",\"fundingTime\":1700000000000,\"fundingRate\":\"0.00010000\",\"markPrice\":\"37000.0\"},"
                + "{\"symbol\":\"BTCUSDT\",\"fundingTime\":1700028800000,\"fundingRate\":\"-0.00025000\",\"markPrice\":\"37100.0\"}]";

        List<MarketSeriesPoint> pts = MarketSeriesStore.parseFundingRateHistory(json);

        assertThat(pts).hasSize(2);
        assertThat(pts.get(0).ts()).isEqualTo(1700000000000L);
        assertThat(pts.get(0).value()).isEqualByComparingTo("0.0001");
        assertThat(pts.get(1).ts()).isEqualTo(1700028800000L);
        assertThat(pts.get(1).value()).isEqualByComparingTo("-0.00025");
    }

    @Test
    void parsesFearGreedSecondsToMillis() {
        // alternative.me /fng: {"data":[{value(字符串0-100), value_classification, timestamp(秒,字符串)}]}
        String json = "{\"name\":\"Fear and Greed Index\",\"data\":["
                + "{\"value\":\"40\",\"value_classification\":\"Fear\",\"timestamp\":\"1700000000\"},"
                + "{\"value\":\"72\",\"value_classification\":\"Greed\",\"timestamp\":\"1699913600\"}]}";

        List<MarketSeriesPoint> pts = MarketSeriesStore.parseFearGreed(json);

        assertThat(pts).hasSize(2);
        assertThat(pts.get(0).ts()).isEqualTo(1700000000000L);   // 秒 → 毫秒
        assertThat(pts.get(0).value()).isEqualByComparingTo("40");
        assertThat(pts.get(1).ts()).isEqualTo(1699913600000L);
        assertThat(pts.get(1).value()).isEqualByComparingTo("72");
    }

    @Test
    void blankOrEmptyReturnsEmpty() {
        assertThat(MarketSeriesStore.parseFundingRateHistory("")).isEmpty();
        assertThat(MarketSeriesStore.parseFundingRateHistory(null)).isEmpty();
        assertThat(MarketSeriesStore.parseFearGreed("")).isEmpty();
        assertThat(MarketSeriesStore.parseFearGreed("{}")).isEmpty();   // 无 data 数组
    }
}
