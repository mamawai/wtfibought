package com.mawai.wiibservice.agent.trading.entry.model;

public record EntryStrategyResult(EntryStrategyCandidate candidate, String rejectReason) {

    public static EntryStrategyResult accept(EntryStrategyCandidate candidate) {
        return new EntryStrategyResult(candidate, null);
    }

    public static EntryStrategyResult reject(String path, String reason) {
        return new EntryStrategyResult(null, path + ": " + reason);
    }
}
