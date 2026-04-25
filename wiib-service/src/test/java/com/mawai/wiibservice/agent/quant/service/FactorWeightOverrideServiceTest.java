package com.mawai.wiibservice.agent.quant.service;

import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.Direction;
import com.mawai.wiibservice.agent.quant.domain.HorizonForecast;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import com.mawai.wiibservice.agent.quant.judge.HorizonJudge;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactorWeightOverrideServiceTest {

    @Test
    void disabledKeepsBaseWeight() {
        FactorWeightOverrideService service = service(false, """
                {"rules":[{"agent":"regime","horizon":"20_30","regime":"SHOCK","multiplier":1.5}]}
                """);

        assertEquals(0.25, service.apply("regime", "20_30", MarketRegime.SHOCK, 0.25), 1e-9);
    }

    @Test
    void enabledAppliesMatchingRegimeMultiplierOnly() {
        FactorWeightOverrideService service = service(true, """
                {"rules":[{"agent":"momentum","horizon":"0_10","regime":"SHOCK","multiplier":0.0}]}
                """);

        assertEquals(0.0, service.apply("momentum", "0_10", MarketRegime.SHOCK, 0.25), 1e-9);
        assertEquals(0.25, service.apply("momentum", "0_10", MarketRegime.RANGE, 0.25), 1e-9);
    }

    @Test
    void horizonJudgeCanReplayBaselineAndOverrideWithoutChangingGlobalSwitch() {
        FactorWeightOverrideService service = service(false, """
                {"rules":[{"agent":"momentum","horizon":"0_10","regime":"SHOCK","multiplier":0.0}]}
                """);
        List<AgentVote> votes = List.of(
                new AgentVote("momentum", "0_10", Direction.LONG, 0.8, 0.7, 20, 20, List.of(), List.of()),
                new AgentVote("regime", "0_10", Direction.SHORT, -0.3, 0.7, 20, 20, List.of(), List.of())
        );

        HorizonForecast baseline = new HorizonJudge("0_10", Map.of(), service, false)
                .judge(votes, BigDecimal.valueOf(100), List.of(), MarketRegime.SHOCK);
        HorizonForecast override = new HorizonJudge("0_10", Map.of(), service, true)
                .judge(votes, BigDecimal.valueOf(100), List.of(), MarketRegime.SHOCK);

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
