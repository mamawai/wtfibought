package com.mawai.wiibagent.toolkit;

import com.mawai.wiibquant.market.service.MarketDataService;
import com.mawai.wiibquant.market.service.MarketDataService.DerivSeries;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DerivativesToolkitTest {

    /** 2025-09-04 15:00 UTC = 北京 23:00；下一根是 16:00 UTC */
    private static final long T1 = 1_756_998_000_000L;
    private static final long T2 = T1 + 3_600_000L;

    private final MarketDataService dataService = mock(MarketDataService.class);
    private final DerivativesToolkit toolkit = new DerivativesToolkit(dataService);

    @Test
    void 四条线按时间对齐_旧在前_缺的线列在unavailable() {
        when(dataService.derivativesSeries(DerivSeries.OPEN_INTEREST, "BTCUSDT", "1h", 30)).thenReturn("""
                [{"symbol":"BTCUSDT","sumOpenInterest":"78219.287","sumOpenInterestValue":"6100000000.50","timestamp":%d},
                 {"symbol":"BTCUSDT","sumOpenInterest":"78500.000","sumOpenInterestValue":"6150000000.00","timestamp":%d}]"""
                .formatted(T1, T2));
        when(dataService.derivativesSeries(DerivSeries.ACCOUNT_LSR, "BTCUSDT", "1h", 30)).thenReturn("""
                [{"symbol":"BTCUSDT","longShortRatio":"1.8520","longAccount":"0.6494","shortAccount":"0.3506","timestamp":%d},
                 {"symbol":"BTCUSDT","longShortRatio":"1.9000","longAccount":"0.6552","shortAccount":"0.3448","timestamp":%d}]"""
                .formatted(T1, T2));
        when(dataService.derivativesSeries(DerivSeries.TOP_TRADER_POSITION_LSR, "BTCUSDT", "1h", 30)).thenReturn(null);
        // 主动买卖只到了最新一个点
        when(dataService.derivativesSeries(DerivSeries.TAKER_LSR, "BTCUSDT", "1h", 30)).thenReturn("""
                [{"buySellRatio":"0.9800","buyVol":"1200.5","sellVol":"1225.0","timestamp":%d}]""".formatted(T2));

        JsonNode out = MAPPER.readTree(toolkit.derivativesHistory("BTCUSDT", "1H"));

        assertThat(out.get("available").asBoolean()).isTrue();
        assertThat(out.get("period").asString()).isEqualTo("1h");
        assertThat(out.get("unavailable")).hasSize(1);
        assertThat(out.get("unavailable").get(0).asString()).isEqualTo("topTraderPositionLsr");
        JsonNode series = out.get("series");
        assertThat(series).hasSize(2);
        JsonNode first = series.get(0);
        assertThat(first.get("time").asString()).isEqualTo("2025-09-04 23:00");
        assertThat(first.get("oi").asDouble()).isEqualTo(78219.287);
        assertThat(first.get("accountLsr").asDouble()).isEqualTo(1.852);
        assertThat(first.has("takerBuySellRatio")).isFalse();
        JsonNode last = series.get(1);
        assertThat(last.get("takerBuySellRatio").asDouble()).isEqualTo(0.98);
        assertThat(last.get("takerSellVol").asDouble()).isEqualTo(1225.0);
    }

    @Test
    void 四条线都没有回不可用() {
        assertThat(toolkit.derivativesHistory("BTCUSDT", "1w"))
                .contains("\"available\":false").contains("BTCUSDT 1w");
    }
}
