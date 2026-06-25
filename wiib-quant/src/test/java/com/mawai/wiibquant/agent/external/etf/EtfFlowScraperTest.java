package com.mawai.wiibquant.agent.external.etf;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EtfFlowScraperTest {

    @Test
    void parseLatestReadsTotalFlowFromFarsideTable() throws Exception {
        EtfFlowScraper scraper = new EtfFlowScraper(null);
        EtfFlowScraper.EtfFlowPoint point = scraper.parseLatest(sampleHtml());

        assertEquals(java.time.LocalDate.of(2026, 4, 24), point.date());
        assertEquals(new BigDecimal("111.7"), point.totalFlowUsdMillion());
    }

    @Test
    void parseAllReturnsEveryDailyRowAscendingSkippingTotalRow() throws Exception {
        EtfFlowScraper scraper = new EtfFlowScraper(null);
        List<EtfFlowScraper.EtfFlowPoint> points = scraper.parseAll(sampleHtml());

        // 样本含 2 个日期行 + 1 个 "Total" 汇总行；汇总行 Date 列非日期 → 自然排除
        assertEquals(2, points.size());
        assertEquals(java.time.LocalDate.of(2026, 4, 23), points.get(0).date());   // 升序：先 23 日
        assertEquals(new BigDecimal("51.1"), points.get(0).totalFlowUsdMillion());
        assertEquals(java.time.LocalDate.of(2026, 4, 24), points.get(1).date());
        assertEquals(new BigDecimal("111.7"), points.get(1).totalFlowUsdMillion());
    }

    @Test
    void parseLatestFinalizedSkipsCurrentNewYorkPlaceholderRow() {
        EtfFlowScraper scraper = new EtfFlowScraper(null);
        EtfFlowScraper.EtfFlowPoint rawLatest = scraper.parseLatest(currentNewYorkDayPlaceholderHtml());
        EtfFlowScraper.EtfFlowPoint point = scraper.parseLatestFinalized(
                currentNewYorkDayPlaceholderHtml(), LocalDate.of(2026, 6, 10));

        assertEquals(LocalDate.of(2026, 6, 9), rawLatest.date());
        assertEquals(new BigDecimal("0.0"), rawLatest.totalFlowUsdMillion());
        assertEquals(LocalDate.of(2026, 6, 8), point.date());
        assertEquals(new BigDecimal("-91.4"), point.totalFlowUsdMillion());
    }

    private String sampleHtml() throws Exception {
        return new String(
                getClass().getResourceAsStream("/farside/sample-20260424.html").readAllBytes(),
                StandardCharsets.UTF_8);
    }

    private String currentNewYorkDayPlaceholderHtml() {
        return """
                <!doctype html>
                <html lang="en">
                <body>
                <table>
                    <tr>
                        <th>Date</th>
                        <th>IBIT</th>
                        <th>FBTC</th>
                        <th>Total</th>
                    </tr>
                    <tr>
                        <td>08 Jun 2026</td>
                        <td>(232.9)</td>
                        <td>59.4</td>
                        <td>(91.4)</td>
                    </tr>
                    <tr>
                        <td>09 Jun 2026</td>
                        <td>-</td>
                        <td>0.0</td>
                        <td>0.0</td>
                    </tr>
                </table>
                </body>
                </html>
                """;
    }
}
