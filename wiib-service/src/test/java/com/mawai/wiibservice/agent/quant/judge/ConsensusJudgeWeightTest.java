package com.mawai.wiibservice.agent.quant.judge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsensusJudgeWeightTest {

    @Test
    void defaultFiveFactorWeightsSumToOnePerHorizon() {
        List<String> agents = List.of("microstructure", "momentum", "regime", "volatility", "news_event");

        for (String horizon : List.of("H6", "H12", "H24")) {
            double sum = agents.stream()
                    .mapToDouble(agent -> ConsensusJudge.baseEvidenceWeight(agent, horizon))
                    .sum();
            assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-12));
        }
    }
}
