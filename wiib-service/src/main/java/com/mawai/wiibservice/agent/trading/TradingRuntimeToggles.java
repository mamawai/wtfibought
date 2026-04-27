package com.mawai.wiibservice.agent.trading;

/**
 * 执行器本轮可见的交易运行时开关快照。
 */
public record TradingRuntimeToggles(
        boolean lowVolTradingEnabled,
        boolean legacyThreshold5of7Enabled,
        boolean legacy5of7ShadowEnabled
) {
    public static TradingRuntimeToggles fromStaticFields() {
        return new TradingRuntimeToggles(
                DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED,
                DeterministicTradingExecutor.LEGACY_THRESHOLD_5OF7_ENABLED,
                DeterministicTradingExecutor.LEGACY_5OF7_SHADOW_ENABLED
        );
    }
}
