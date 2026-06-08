package com.mawai.wiibservice.task;

import com.mawai.wiibservice.agent.config.RuntimeFeatureToggleService;
import com.mawai.wiibservice.agent.quant.domain.QuantCycleCompleteEvent;
import com.mawai.wiibservice.agent.risk.CircuitBreakerService;
import com.mawai.wiibservice.agent.trading.runtime.TradingExecutionState;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.AiTradingDecisionMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.QuantSignalDecisionMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesRiskService;
import com.mawai.wiibservice.service.FuturesTradingService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiTradingSchedulerTest {

    @Test
    void researchQuantCompleteEventDoesNotSubmitTradingCycle() throws Exception {
        AiTradingScheduler scheduler = newScheduler();
        atomicLongField(scheduler, "aiUserId").set(1L);

        scheduler.onQuantCycleComplete(new QuantCycleCompleteEvent(this, "BTCUSDT", "research"));

        assertThat(atomicIntegerField(scheduler, "cycleCounter").get()).isZero();
    }

    private static AiTradingScheduler newScheduler() {
        return new AiTradingScheduler(
                mock(UserMapper.class),
                mock(FuturesTradingService.class),
                mock(FuturesRiskService.class),
                mock(FuturesPositionMapper.class),
                mock(QuantForecastCycleMapper.class),
                mock(QuantSignalDecisionMapper.class),
                mock(AiTradingDecisionMapper.class),
                mock(CacheService.class),
                mock(CircuitBreakerService.class),
                mock(TradingExecutionState.class),
                mock(RuntimeFeatureToggleService.class),
                mock(TradingConfig.class));
    }

    private static AtomicLong atomicLongField(Object target, String fieldName) throws Exception {
        return (AtomicLong) field(target, fieldName).get(target);
    }

    private static AtomicInteger atomicIntegerField(Object target, String fieldName) throws Exception {
        return (AtomicInteger) field(target, fieldName).get(target);
    }

    private static Field field(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }
}
