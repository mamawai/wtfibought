package com.mawai.wiibservice.agent.trading;

record EntryStrategyResult(EntryStrategyCandidate candidate, String rejectReason) {

    static EntryStrategyResult accept(EntryStrategyCandidate candidate) {
        return new EntryStrategyResult(candidate, null);
    }

    static EntryStrategyResult reject(String path, String reason) {
        return new EntryStrategyResult(null, path + ": " + reason);
    }
}
