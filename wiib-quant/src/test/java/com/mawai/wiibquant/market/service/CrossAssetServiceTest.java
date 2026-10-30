package com.mawai.wiibquant.market.service;

import com.mawai.wiibquant.external.yahoo.YahooFinanceClient;
import com.mawai.wiibquant.market.service.CrossAssetService.Quote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossAssetServiceTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final String SPX = """
            {"chart":{"result":[{"meta":{"symbol":"^GSPC","regularMarketPrice":7720.39013671875,
             "regularMarketChangePercent":-0.30462,"chartPreviousClose":7663.43,"regularMarketTime":1793392366}}],"error":null}}""";
    private static final String NOT_FOUND = """
            {"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}""";

    private final YahooFinanceClient yahoo = mock(YahooFinanceClient.class);
    private final CrossAssetService service = new CrossAssetService(yahoo);

    @Test
    void 只留拿到的_数值两位小数_5日涨跌按窗口前收盘算() {
        when(yahoo.chart5d("^GSPC")).thenReturn(SPX);
        when(yahoo.chart5d("^VIX")).thenReturn(NOT_FOUND);
        service.nowMs = () -> T0;

        List<Quote> quotes = service.snapshot();

        assertThat(quotes).hasSize(1);
        Quote spx = quotes.getFirst();
        assertThat(spx.name()).isEqualTo("S&P 500");
        assertThat(spx.price()).isEqualByComparingTo("7720.39");
        assertThat(spx.changePct1d()).isEqualByComparingTo("-0.30");
        assertThat(spx.changePct5d()).isEqualByComparingTo("0.74");
        assertThat(spx.timeMs()).isEqualTo(1_793_392_366_000L);
    }

    @Test
    void TTL内复用_过期后取不到兜15分钟内的旧快照_再久就空() {
        when(yahoo.chart5d("^GSPC")).thenReturn(SPX);
        service.nowMs = () -> T0;
        List<Quote> first = service.snapshot();
        service.nowMs = () -> T0 + 30_000;

        assertThat(service.snapshot()).isSameAs(first);
        verify(yahoo, times(1)).chart5d("^GSPC");

        when(yahoo.chart5d(anyString())).thenReturn(null);
        service.nowMs = () -> T0 + 10 * 60_000;
        assertThat(service.snapshot()).isSameAs(first);
        service.nowMs = () -> T0 + 15 * 60_000;
        assertThat(service.snapshot()).isEmpty();
    }
}
