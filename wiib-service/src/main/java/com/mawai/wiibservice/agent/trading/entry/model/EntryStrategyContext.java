package com.mawai.wiibservice.agent.trading.entry.model;

import com.mawai.wiibservice.agent.trading.SymbolProfile;

import com.mawai.wiibservice.agent.trading.MarketContext;

public record EntryStrategyContext(
        MarketContext market,
        SymbolProfile profile,
        String side,
        boolean isLong,
        double confidence
) {
}
