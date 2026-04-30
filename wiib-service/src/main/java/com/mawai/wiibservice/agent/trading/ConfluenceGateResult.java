package com.mawai.wiibservice.agent.trading;

import java.util.List;

record ConfluenceGateResult(int score, int total, int required, List<String> hits) {

    boolean passed() {
        return score >= required;
    }

    String hitSummary() {
        return hits.isEmpty() ? "无" : String.join("/", hits);
    }
}
