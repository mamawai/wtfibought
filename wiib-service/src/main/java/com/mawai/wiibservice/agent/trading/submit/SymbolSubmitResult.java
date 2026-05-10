package com.mawai.wiibservice.agent.trading.submit;

public record SymbolSubmitResult(
        String symbol,
        SubmitStatus status,
        String reason
) {}
