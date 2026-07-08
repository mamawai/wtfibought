package com.mawai.wiibquant.agent.quant.signal;

import com.mawai.wiibquant.agent.quant.domain.AgentVote;
import com.mawai.wiibquant.agent.quant.domain.Direction;
import com.mawai.wiibquant.agent.quant.domain.signal.Signal;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalGroup;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalLean;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignalExtractorTest {

    private final SignalExtractor extractor = new SignalExtractor();

    @Test
    void translatesKnownFlagsDedupesAndDropsUnknown() {
        List<AgentVote> votes = List.of(
                vote("microstructure", "H6", List.of("BID_DOMINANT", "HIGH_FUNDING"), List.of("SPOT_PERP_DIVERGENCE")),
                vote("microstructure", "H12", List.of("BID_DOMINANT", "HEAVY_LONG_LIQ"), List.of()),
                vote("momentum", "H6", List.of("MA_BULLISH_5M", "MACD_GOLDEN_15M", "UNKNOWN_FLAG_XYZ"), List.of()),
                vote("volatility", "H6", List.of(), List.of("EXTREME_VOLATILITY", "NO_DATA"))
        );

        SignalPanel panel = extractor.extract(votes);

        // BID_DOMINANT 去重为 1；UNKNOWN_FLAG_XYZ / NO_DATA 忽略 → 共 7 条
        assertThat(panel.signals()).hasSize(7);
        assertThat(codes(panel)).containsExactlyInAnyOrder(
                "BID_DOMINANT", "HIGH_FUNDING", "SPOT_PERP_DIVERGENCE", "HEAVY_LONG_LIQ",
                "MA_BULLISH_5M", "MACD_GOLDEN_15M", "EXTREME_VOLATILITY");
        assertThat(codes(panel)).doesNotContain("UNKNOWN_FLAG_XYZ", "NO_DATA");
    }

    @Test
    void crowdingFlagsLeanReverse() {
        SignalPanel panel = extractor.extract(
                List.of(vote("microstructure", "H6", List.of("HIGH_FUNDING", "LSR_EXTREME_LONG"), List.of())));

        Signal funding = find(panel, "HIGH_FUNDING");
        assertThat(funding.lean()).isEqualTo(SignalLean.BEARISH);
        assertThat(funding.group()).isEqualTo(SignalGroup.POSITIONING);
        assertThat(find(panel, "LSR_EXTREME_LONG").lean()).isEqualTo(SignalLean.BEARISH);
    }

    @Test
    void prefixFlagKeepsTimeframeSuffixInLabel() {
        SignalPanel panel = extractor.extract(
                List.of(vote("momentum", "H6", List.of("MA_BULLISH_5M"), List.of())));

        Signal ma = find(panel, "MA_BULLISH_5M");
        assertThat(ma.lean()).isEqualTo(SignalLean.BULLISH);
        assertThat(ma.group()).isEqualTo(SignalGroup.MOMENTUM);
        assertThat(ma.label()).contains("5M");
    }

    @Test
    void netLeanCountsByGroup() {
        SignalPanel panel = extractor.extract(List.of(
                vote("microstructure", "H6", List.of("HIGH_FUNDING", "HEAVY_LONG_LIQ"), List.of()), // POSITIONING -2
                vote("momentum", "H6", List.of("MA_BULLISH_5M", "MACD_GOLDEN_15M"), List.of())       // MOMENTUM +2
        ));

        assertThat(panel.netLean(SignalGroup.POSITIONING)).isEqualTo(-2);
        assertThat(panel.netLean(SignalGroup.MOMENTUM)).isEqualTo(2);
        assertThat(panel.byGroup()).containsOnlyKeys(SignalGroup.POSITIONING, SignalGroup.MOMENTUM);
    }

    private static AgentVote vote(String agent, String horizon, List<String> reasons, List<String> risks) {
        return new AgentVote(agent, horizon, Direction.NO_TRADE, 0, 0.5, 0, 20, reasons, risks);
    }

    private static List<String> codes(SignalPanel panel) {
        return panel.signals().stream().map(Signal::code).toList();
    }

    private static Signal find(SignalPanel panel, String code) {
        return panel.signals().stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
    }
}
