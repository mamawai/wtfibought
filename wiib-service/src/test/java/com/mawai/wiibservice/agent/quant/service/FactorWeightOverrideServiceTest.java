package com.mawai.wiibservice.agent.quant.service;

import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.Direction;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import com.mawai.wiibservice.agent.quant.judge.ConsensusForecast;
import com.mawai.wiibservice.agent.quant.judge.ConsensusJudge;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.Map;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactorWeightOverrideServiceTest {

    @Test
    void disabledKeepsBaseWeight() {
        FactorWeightOverrideService service = service(false, """
                {"rules":[{"agent":"regime","horizon":"H24","regime":"SHOCK","multiplier":1.5}]}
                """);

        assertEquals(0.25, service.apply("regime", "H24", MarketRegime.SHOCK, 0.25), 1e-9);
    }

    @Test
    void enabledAppliesMatchingRegimeMultiplierOnly() {
        FactorWeightOverrideService service = service(true, """
                {"rules":[{"agent":"momentum","horizon":"H6","regime":"SHOCK","multiplier":0.0}]}
                """);

        assertEquals(0.0, service.apply("momentum", "H6", MarketRegime.SHOCK, 0.25), 1e-9);
        assertEquals(0.25, service.apply("momentum", "H6", MarketRegime.RANGE, 0.25), 1e-9);
    }

    @Test
    void consensusJudgeCanReplayBaselineAndOverrideWithoutChangingGlobalSwitch() {
        FactorWeightOverrideService service = service(false, """
                {"rules":[{"agent":"momentum","horizon":"H6","regime":"SHOCK","multiplier":0.0}]}
                """);
        List<AgentVote> votes = List.of(
                new AgentVote("momentum", "H6", Direction.LONG, 0.8, 0.7, 20, 20, List.of(), List.of()),
                new AgentVote("regime", "H6", Direction.SHORT, -0.3, 0.7, 20, 20, List.of(), List.of())
        );

        ConsensusForecast baseline = new ConsensusJudge("H6", 1, 0.20, Map.of(), service,
                MarketRegime.SHOCK, false).judge(votes);
        ConsensusForecast override = new ConsensusJudge("H6", 1, 0.20, Map.of(), service,
                MarketRegime.SHOCK, true).judge(votes);

        assertEquals(Direction.LONG, baseline.direction());
        assertEquals(Direction.SHORT, override.direction());
        assertEquals(false, FactorWeightOverrideService.FACTOR_WEIGHT_OVERRIDE_ENABLED);
    }

    private FactorWeightOverrideService service(boolean enabled, String json) {
        ByteArrayResource resource = new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
        FactorWeightOverrideService service = new FactorWeightOverrideService(enabled, resource);
        service.load();
        return service;
    }
}
