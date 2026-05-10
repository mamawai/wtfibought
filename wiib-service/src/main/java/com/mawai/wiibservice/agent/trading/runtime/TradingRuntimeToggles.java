package com.mawai.wiibservice.agent.trading.runtime;

import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;

/**
 * 执行器本轮可见的交易运行时开关快照。
 */
public record TradingRuntimeToggles(
        boolean lowVolTradingEnabled,
        boolean playbookExitEnabled
) {
    public TradingRuntimeToggles(boolean lowVolTradingEnabled) {
        this(lowVolTradingEnabled, false);
    }

    public static TradingRuntimeToggles fromStaticFields() {
        return new TradingRuntimeToggles(
                DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED,
                DeterministicTradingExecutor.PLAYBOOK_EXIT_ENABLED
        );
    }
}
