package com.mawai.wiibservice.agent.external.etf;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EtfFlowScraperTest {

    @Test
    void parseLatestReadsTotalFlowFromFarsideTable() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/farside/sample-20260424.html").readAllBytes(),
                StandardCharsets.UTF_8);

        EtfFlowScraper scraper = new EtfFlowScraper(null);
        EtfFlowScraper.EtfFlowPoint point = scraper.parseLatest(html);

        assertEquals(java.time.LocalDate.of(2026, 4, 24), point.date());
        assertEquals(new BigDecimal("111.7"), point.totalFlowUsdMillion());
    }
}
