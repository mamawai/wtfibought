package com.mawai.wiibservice.agent.research.series;

import com.mawai.wiibcommon.entity.FactorHistory;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.FactorHistoryMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void epochMillisRoundTripsThroughUtc() {
        // factor_history 存 UTC LocalDateTime、research 用 epoch ms —— 互转必须无损且按 UTC（时区错会让 as-of 偏移）
        long ms = 1700000000000L;
        assertThat(MarketSeriesStore.toMs(MarketSeriesStore.toLdt(ms))).isEqualTo(ms);
        assertThat(MarketSeriesStore.toLdt(0L)).isEqualTo(LocalDateTime.of(1970, 1, 1, 0, 0, 0));
    }

    @Test
    void loadAppliesTPlusOneAvailabilityLagForDailyFactors() {
        FactorHistoryMapper mapper = mock(FactorHistoryMapper.class);
        MarketSeriesStore store = new MarketSeriesStore(null, mapper);
        long from = MarketSeriesStore.toMs(LocalDateTime.of(2026, 1, 2, 0, 0));
        long to = MarketSeriesStore.toMs(LocalDateTime.of(2026, 1, 3, 0, 0));

        when(mapper.selectRange(
                MarketSeriesStore.GLOBAL,
                SeriesCode.FEAR_GREED.factorName(),
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 3, 0, 0)))
                .thenReturn(List.of(
                        row(LocalDateTime.of(2026, 1, 1, 0, 0), "70"),
                        row(LocalDateTime.of(2026, 1, 2, 0, 0), "95")));

        List<MarketSeriesPoint> pts = store.load(MarketSeriesStore.GLOBAL, SeriesCode.FEAR_GREED, from, to);

        // 1月2日的决策窗口只能看到1月1日发布后可用的数据；1月2日原始点要到1月3日才可用。
        assertThat(pts).hasSize(1);
        assertThat(pts.get(0).ts()).isEqualTo(from);
        assertThat(pts.get(0).value()).isEqualByComparingTo("70");
    }

    @Test
    void loadKeepsFundingAtObservedTimestamp() {
        FactorHistoryMapper mapper = mock(FactorHistoryMapper.class);
        MarketSeriesStore store = new MarketSeriesStore(null, mapper);
        long from = MarketSeriesStore.toMs(LocalDateTime.of(2026, 1, 2, 0, 0));
        long to = MarketSeriesStore.toMs(LocalDateTime.of(2026, 1, 3, 0, 0));
        LocalDateTime fundingTime = LocalDateTime.of(2026, 1, 2, 8, 0);

        when(mapper.selectRange(
                "BTCUSDT",
                SeriesCode.FUNDING.factorName(),
                LocalDateTime.of(2026, 1, 2, 0, 0),
                LocalDateTime.of(2026, 1, 3, 0, 0)))
                .thenReturn(List.of(row(fundingTime, "0.0001")));

        List<MarketSeriesPoint> pts = store.load("BTCUSDT", SeriesCode.FUNDING, from, to);

        assertThat(pts).hasSize(1);
        assertThat(pts.get(0).ts()).isEqualTo(MarketSeriesStore.toMs(fundingTime));
        assertThat(pts.get(0).value()).isEqualByComparingTo("0.0001");
    }

    @Test
    void backfillFundingPagesForwardWithStartAndEndTime() {
        BinanceRestClient client = mock(BinanceRestClient.class);
        FactorHistoryMapper mapper = mock(FactorHistoryMapper.class);
        MarketSeriesStore store = new MarketSeriesStore(client, mapper);
        long from = 1700000000000L;
        long stepMs = 1000L;
        long secondCursor = from + 999 * stepMs + 1;
        long secondPoint = from + 1000 * stepMs;
        long to = secondPoint + stepMs;

        when(client.getFundingRateHistory("BTCUSDT", 1000, from, to))
                .thenReturn(fundingJson(from, 1000, stepMs));
        when(client.getFundingRateHistory("BTCUSDT", 1000, secondCursor, to))
                .thenReturn(fundingJson(secondPoint, 1, stepMs));

        int rows = store.backfillFunding("BTCUSDT", from, to);

        assertThat(rows).isEqualTo(1001);
        verify(client).getFundingRateHistory("BTCUSDT", 1000, from, to);
        verify(client).getFundingRateHistory("BTCUSDT", 1000, secondCursor, to);
        verify(client, never()).getFundingRateHistory(eq("BTCUSDT"), eq(1000), anyLong());
        verify(mapper, times(1001)).upsert(any(FactorHistory.class));
    }

    private static FactorHistory row(LocalDateTime observedAt, String value) {
        FactorHistory row = new FactorHistory();
        row.setObservedAt(observedAt);
        row.setFactorValue(new BigDecimal(value));
        return row;
    }

    private static String fundingJson(long startMs, int count, long stepMs) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"symbol\":\"BTCUSDT\",\"fundingTime\":")
                    .append(startMs + i * stepMs)
                    .append(",\"fundingRate\":\"0.00010000\"}");
        }
        return out.append(']').toString();
    }
}
