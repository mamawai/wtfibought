package com.mawai.wiibservice.agent.trading;

public record SymbolSubmitResult(
        String symbol,
        SubmitStatus status,
        String reason
) {}
