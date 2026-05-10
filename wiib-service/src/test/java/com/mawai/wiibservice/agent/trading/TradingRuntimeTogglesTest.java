package com.mawai.wiibservice.agent.trading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradingRuntimeTogglesTest {

    @AfterEach
    void resetStaticToggles() {
        DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED = true;
        DeterministicTradingExecutor.PLAYBOOK_EXIT_ENABLED = false;
    }

    @Test
    void legacyConstructorDefaultsPlaybookExitOff() {
        TradingRuntimeToggles toggles = new TradingRuntimeToggles(true);

        assertThat(toggles.lowVolTradingEnabled()).isTrue();
        assertThat(toggles.playbookExitEnabled()).isFalse();
    }

    @Test
    void fromStaticFieldsCarriesPlaybookExitToggle() {
        DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED = false;
        DeterministicTradingExecutor.PLAYBOOK_EXIT_ENABLED = true;

        TradingRuntimeToggles toggles = TradingRuntimeToggles.fromStaticFields();

        assertThat(toggles.lowVolTradingEnabled()).isFalse();
        assertThat(toggles.playbookExitEnabled()).isTrue();
    }
}
