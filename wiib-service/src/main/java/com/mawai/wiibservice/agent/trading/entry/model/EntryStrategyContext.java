package com.mawai.wiibservice.agent.trading.entry.model;

import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;

import com.mawai.wiibservice.agent.trading.runtime.MarketContext;

public record EntryStrategyContext(
        String symbol,
        KlineInterval decisionInterval,
        MarketContext market,
        SymbolProfile profile,
        String side,
        boolean isLong,
        double confidence
) {
    public EntryStrategyContext(MarketContext market,
                                SymbolProfile profile,
                                String side,
                                boolean isLong,
                                double confidence) {
        this(null, market != null ? market.decisionInterval : KlineInterval.M5,
                market, profile, side, isLong, confidence);
    }
}
