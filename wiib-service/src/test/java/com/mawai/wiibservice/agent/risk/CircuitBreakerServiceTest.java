package com.mawai.wiibservice.agent.risk;

import com.mawai.wiibcommon.entity.StrategyPathStatus;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.StrategyPathStatusMapper;
import com.mawai.wiibservice.mapper.TradeAttributionMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.CacheService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CircuitBreakerServiceTest {

    @Test
    void rejectsDisabledStrategyPathEvenWhenAccountBreakerDisabled() {
        CacheService cacheService = mock(CacheService.class);
        StrategyPathStatusMapper pathStatusMapper = mock(StrategyPathStatusMapper.class);
        CircuitBreakerService service = new CircuitBreakerService(
                cacheService,
                mock(UserMapper.class),
                mock(FuturesPositionMapper.class),
                mock(TradeAttributionMapper.class),
                pathStatusMapper);

        StrategyPathStatus disabled = new StrategyPathStatus();
        disabled.setPath("BREAKOUT");
        disabled.setEnabled(false);
        disabled.setDisabledReason("人工禁用");
        when(pathStatusMapper.selectById("BREAKOUT")).thenReturn(disabled);

        CircuitBreakerService.OpenDecision decision = service.allowOpen(1L, "BREAKOUT");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("路径已禁用 path=BREAKOUT");
        verify(pathStatusMapper).selectById("BREAKOUT");
        verifyNoInteractions(cacheService);
    }
}
