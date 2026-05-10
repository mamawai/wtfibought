package com.mawai.wiibservice.agent.trading.submit;

import java.util.List;

public record TradingCycleSubmitResult(
        int cycleNo,
        List<SymbolSubmitResult> items
) {}
