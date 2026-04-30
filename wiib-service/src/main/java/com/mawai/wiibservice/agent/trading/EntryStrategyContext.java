package com.mawai.wiibservice.agent.trading;

record EntryStrategyContext(
        MarketContext market,
        SymbolProfile profile,
        String side,
        boolean isLong,
        double confidence
) {
}
