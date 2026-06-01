package com.mawai.wiibservice.agent.external.etf;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

    private String sampleHtml() throws Exception {
        return new String(
                getClass().getResourceAsStream("/farside/sample-20260424.html").readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
