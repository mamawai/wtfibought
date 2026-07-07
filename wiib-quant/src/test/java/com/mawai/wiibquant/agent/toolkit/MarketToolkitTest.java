package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.MarketRegime;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragileDirection;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityLevel;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityScore;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketToolkitTest {

    private final MarketDataService dataService = mock(MarketDataService.class);
    private final MarketToolkit toolkit = new MarketToolkit(dataService);

    /** record 无法可靠 mock（accessor 直通），造真实 snapshot：只填工具输出关心的字段，其余 0/null 由 compact constructor 兜底。 */
    private MarketAssembly assemblyWithSnapshot() {
        FeatureSnapshot s = new FeatureSnapshot(
                "BTCUSDT", LocalDateTime.now(), new BigDecimal("65000"), null, null, null,
                null, null,
                0.1, null, 4.2, 0,
                0.15, 0.05, 0, 0.1, 0.02,
                0.35, 0, 0, 0.4,
                -0.2, 1_200_000,
                0.3, -0.1, 72, "Greed",
                null, new BigDecimal("350"), null, false,
                52.0, 48.0, -0.05, 0.02,
                MarketRegime.RANGE, null, null, 0.6, "NONE");
        return new MarketAssembly("BTCUSDT", true, Map.of(),
                Map.of("price_change_map", Map.of("24h", "+2.3%")),
                s, SignalPanel.empty(),
                new FragilityScore(61, FragilityLevel.HIGH, 0.7, 0.4, 0.7,
                        FragileDirection.DOWN, "多头拥挤+清算邻近，下行脆弱"),
                Instant.now());
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
