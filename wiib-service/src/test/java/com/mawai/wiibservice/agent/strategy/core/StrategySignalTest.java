package com.mawai.wiibservice.agent.strategy.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StrategySignalTest {

    @Test
    void limitConstructorCarriesOrderType() {
        StrategySignal s = new StrategySignal("FIBO", "BTCUSDT", "LONG", true,
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"),
                0.5, "reason", 1000L, "LIMIT");
        assertThat(s.orderType()).isEqualTo("LIMIT");
    }

    @Test
    void legacyConstructorDefaultsToMarket() {
        StrategySignal s = new StrategySignal("FIBO", "BTCUSDT", "LONG", true,
                new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("110"),
                0.5, "reason", 1000L);
        assertThat(s.orderType()).isEqualTo("MARKET");
    }
}
