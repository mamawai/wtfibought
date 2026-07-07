package com.mawai.wiibquant.agent.toolkit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketToolkitTest {

    private final MarketDataService dataService = mock(MarketDataService.class);
    private final MarketToolkit toolkit = new MarketToolkit(dataService);

    /** 共用造件见 TestAssemblies（record 无法可靠 mock，统一真实构造）。 */
    private MarketAssembly assemblyWithSnapshot() {
        return TestAssemblies.available();
    }

    @Test
    void marketSnapshotOutputsKeyFields() {
        when(dataService.assemble("BTCUSDT")).thenReturn(assemblyWithSnapshot());

        String json = toolkit.marketSnapshot("BTCUSDT");

        assertThat(json).contains("65000").contains("fundingDeviation").contains("fearGreed")
                .contains("price_change").contains("RANGE");
    }

    @Test
    void fragilityOutputsScoreAndHeadline() {
        when(dataService.assemble("BTCUSDT")).thenReturn(assemblyWithSnapshot());

        String json = toolkit.fragility("BTCUSDT");

        assertThat(json).contains("61").contains("HIGH").contains("下行脆弱");
    }

    @Test
    void optionIvOutputsSummary() {
        when(dataService.assemble("BTCUSDT")).thenReturn(assemblyWithSnapshot());

        String json = toolkit.optionIv("BTCUSDT");

        assertThat(json).contains("DVOL=52");
    }

    @Test
    void unavailableAssemblyDegradesGracefully() {
        when(dataService.assemble("BTCUSDT")).thenReturn(MarketAssembly.unavailable("BTCUSDT", Map.of()));

        assertThat(toolkit.marketSnapshot("BTCUSDT")).contains("\"available\":false");
        assertThat(toolkit.fragility("BTCUSDT")).contains("\"available\":false");
        assertThat(toolkit.optionIv("BTCUSDT")).contains("\"available\":false");
    }
}
