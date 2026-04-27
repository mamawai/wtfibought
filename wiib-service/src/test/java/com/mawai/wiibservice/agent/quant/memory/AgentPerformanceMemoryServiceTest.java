package com.mawai.wiibservice.agent.quant.memory;

import com.mawai.wiibservice.mapper.QuantAgentVoteMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentPerformanceMemoryServiceTest {

    @Test
    void loadsOnlyEnoughProgrammaticAgentAccuracySamples() {
        QuantAgentVoteMapper mapper = mock(QuantAgentVoteMapper.class);
        when(mapper.selectAgentPerformanceStats("BTCUSDT", "TREND_UP", 30)).thenReturn(List.of(
                Map.of("agent", "momentum", "horizon", "0_10",
                        "sample_count", 25L, "accuracy", new BigDecimal("0.62"),
                        "avg_edge_bps", new BigDecimal("12.5"), "bad_rate", new BigDecimal("0.20")),
                Map.of("agent", "news_event", "horizon", "0_10",
                        "sample_count", 8L, "accuracy", new BigDecimal("0.80"),
                        "avg_edge_bps", new BigDecimal("20.0"), "bad_rate", new BigDecimal("0.10"))
        ));

        Map<String, Map<String, Double>> result =
                new AgentPerformanceMemoryService(mapper).loadAccuracy("BTCUSDT", "trend_up");

        assertThat(result).containsKey("momentum");
        assertThat(result.get("momentum")).containsEntry("0_10", 0.62);
        assertThat(result).doesNotContainKey("news_event");
        verify(mapper).selectAgentPerformanceStats("BTCUSDT", "TREND_UP", 30);
    }
}
