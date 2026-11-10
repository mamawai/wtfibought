package com.mawai.wiibquant.market.collect;

import com.mawai.wiibcommon.market.OrderFlowAggregator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 原始读数：各数据源的最新点原样给出、对照点取对了位置；没取到的那段不出现，爆仓没记录是 0 */
class MarketReadingsTest {

    /** 2026-11-10 12:00:00 北京时间 */
    private static final long NOW = 1794283200000L;
    private static final long M5 = 300_000L;

    private static Map<String, Object> raw(String key, String json) {
        return Map.of(key, Map.of("ETHUSDT", json));
    }

    @Test
    void accountLongShortGivesLatestPointAndWindowRange() {
        String json = "[{\"longShortRatio\":\"0.98\",\"longAccount\":\"0.495\",\"shortAccount\":\"0.505\",\"timestamp\":" + (NOW - 2 * M5) + "},"
                + "{\"longShortRatio\":\"1.05\",\"longAccount\":\"0.512\",\"shortAccount\":\"0.488\",\"timestamp\":" + (NOW - M5) + "},"
                + "{\"longShortRatio\":\"1.13\",\"longAccount\":\"0.531\",\"shortAccount\":\"0.469\",\"timestamp\":" + NOW + "}]";

        ObjectNode r = MarketReadings.build("ETHUSDT", raw("long_short_ratio_map", json), null, NOW);

        assertThat(r.path("accountLongShort").toString()).isEqualTo(
                "{\"ratio\":1.13,\"longAccount\":0.531,\"shortAccount\":0.469,\"low24h\":0.98,\"high24h\":1.13,\"time\":\"11-10 12:00\"}");
    }

    @Test
    void openInterestGivesLatestOneHourAgoAndEarliest() {
        StringBuilder json = new StringBuilder("[");
        // 14 个点：最早那个 1000 币，1 小时前（倒数第 13 个）是 1001，最新 1013
        for (int i = 0; i < 14; i++) {
            json.append(i == 0 ? "" : ",").append("{\"sumOpenInterest\":\"").append(1000 + i)
                    .append("\",\"sumOpenInterestValue\":\"").append((1000 + i) * 2000)
                    .append("\",\"timestamp\":").append(NOW - (13 - i) * M5).append('}');
        }
        json.append(']');

        ObjectNode oi = (ObjectNode) MarketReadings.build("ETHUSDT", raw("oi_hist_map", json.toString()), null, NOW)
                .path("openInterest");

        assertThat(oi.path("coins").asInt()).isEqualTo(1013);
        assertThat(oi.path("usd").asInt()).isEqualTo(2026000);
        assertThat(oi.path("coins1hAgo").asInt()).isEqualTo(1001);
        assertThat(oi.path("coinsEarliest").asInt()).isEqualTo(1000);
        assertThat(oi.path("earliestTime").asString()).isEqualTo("11-10 10:55");
    }

    /** 两个窗口并排：30 分钟里多头爆了 3000、空头 500 共 3 笔，其中最近 5 分钟只有一笔空头 500 */
    @Test
    void liquidationsSplitByWindowAndSide() {
        String json = "[{\"side\":\"SELL\",\"price\":2000,\"origQty\":1,\"time\":" + (NOW - 20 * 60_000L) + "},"
                + "{\"side\":\"SELL\",\"price\":2000,\"origQty\":0.5,\"time\":" + (NOW - 10 * 60_000L) + "},"
                + "{\"side\":\"BUY\",\"price\":2000,\"origQty\":0.25,\"time\":" + (NOW - 60_000L) + "}]";

        ObjectNode r = MarketReadings.build("ETHUSDT", raw("force_orders_map", json), null, NOW);

        assertThat(r.path("liquidations").toString()).isEqualTo(
                "{\"last5m\":{\"longUsd\":0,\"shortUsd\":500,\"count\":1},\"last30m\":{\"longUsd\":3000,\"shortUsd\":500,\"count\":3}}");
    }

    @Test
    void missingSourcesAreAbsentAndLiquidationsDefaultToZero() {
        ObjectNode r = MarketReadings.build("ETHUSDT", Map.of(), null, NOW);

        assertThat(r.propertyNames()).containsExactly("liquidations");
        assertThat(r.path("liquidations").path("last30m").toString()).isEqualTo("{\"longUsd\":0,\"shortUsd\":0,\"count\":0}");
    }

    @Test
    void bookSumsTopFiveLevelsPerSide() {
        String book = "{\"bids\":[[\"1\",\"1\"],[\"1\",\"1\"],[\"1\",\"1\"],[\"1\",\"1\"],[\"1\",\"1\"],[\"1\",\"1000\"]],"
                + "\"asks\":[[\"1\",\"2.5\"]]}";

        ObjectNode r = MarketReadings.build("ETHUSDT", raw("orderbook_map", book), null, NOW);

        assertThat(r.path("futuresBookTop5").toString()).isEqualTo("{\"bidQty\":5,\"askQty\":2.5}");
    }

    @Test
    void tradesGiveRawAmountsAndNeedEnoughPrints() {
        OrderFlowAggregator flow = mock(OrderFlowAggregator.class);
        when(flow.hasData("ETHUSDT")).thenReturn(true);
        when(flow.getMetrics("ETHUSDT", 180)).thenReturn(new OrderFlowAggregator.Metrics(120000.4, 80000.6, 60000, 0, 42));

        ObjectNode r = MarketReadings.build("ETHUSDT", Map.of(), flow, NOW);

        assertThat(r.path("trades3m").toString()).isEqualTo(
                "{\"takerBuyUsd\":120000,\"takerSellUsd\":80001,\"largeBuyUsd\":60000,\"largeSellUsd\":0,\"largeThresholdUsd\":50000,\"count\":42}");

        when(flow.getMetrics("ETHUSDT", 180)).thenReturn(new OrderFlowAggregator.Metrics(100, 0, 0, 0, 9));
        assertThat(MarketReadings.build("ETHUSDT", Map.of(), flow, NOW).has("trades3m")).isFalse();
    }
}
