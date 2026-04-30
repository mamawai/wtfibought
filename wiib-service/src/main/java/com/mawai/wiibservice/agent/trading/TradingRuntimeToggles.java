package com.mawai.wiibservice.agent.trading;

/**
 * 执行器本轮可见的交易运行时开关快照。
 */
public record TradingRuntimeToggles(
        boolean lowVolTradingEnabled
) {
    public static TradingRuntimeToggles fromStaticFields() {
        return new TradingRuntimeToggles(
                DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED
        );
    }
}
