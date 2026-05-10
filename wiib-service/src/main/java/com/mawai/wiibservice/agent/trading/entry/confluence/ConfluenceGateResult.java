package com.mawai.wiibservice.agent.trading.entry.confluence;

import java.util.List;

public record ConfluenceGateResult(int score, int total, int required, List<String> hits) {

    public boolean passed() {
        return score >= required;
    }

    public String hitSummary() {
        return hits.isEmpty() ? "无" : String.join("/", hits);
    }
}
