package com.mawai.wiibservice.agent.trading;

import java.util.List;

public record TradingCycleSubmitResult(
        int cycleNo,
        List<SymbolSubmitResult> items
) {}
