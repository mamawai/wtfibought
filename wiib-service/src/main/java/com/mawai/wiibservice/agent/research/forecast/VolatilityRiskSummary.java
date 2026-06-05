package com.mawai.wiibservice.agent.research.forecast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 样本外 vol context 汇总：报告/落盘用，不参与预测。 */
public record VolatilityRiskSummary(
        int n,
        int medianExpectedMoveBps,
        int p90ExpectedMoveBps,
        double elevatedOrStressedShare,
        double stressedShare,
        Map<VolatilityRiskTier, Integer> tierCounts,
        String llmContext
) {

    public VolatilityRiskSummary {
        if (n < 0) throw new IllegalArgumentException("n 不能为负");
        if (medianExpectedMoveBps < 0 || p90ExpectedMoveBps < 0) {
            throw new IllegalArgumentException("expected move bps 不能为负");
        }
        if (elevatedOrStressedShare < 0.0 || elevatedOrStressedShare > 1.0
                || stressedShare < 0.0 || stressedShare > 1.0) {
            throw new IllegalArgumentException("share 须落在 [0,1]");
        }
        tierCounts = tierCounts == null ? Map.of() : Map.copyOf(tierCounts);
        llmContext = llmContext == null ? "" : llmContext;
    }

    public static VolatilityRiskSummary from(List<VolatilityRiskContext> contexts) {
        List<VolatilityRiskContext> rows = contexts == null ? List.of() : contexts.stream()
                .filter(c -> c != null)
                .toList();
        int n = rows.size();
        if (n == 0) {
            return new VolatilityRiskSummary(0, 0, 0, 0.0, 0.0, Map.of(), "vol_context_summary{n=0}");
        }

        List<Integer> moves = new ArrayList<>(n);
        Map<VolatilityRiskTier, Integer> counts = new EnumMap<>(VolatilityRiskTier.class);
        for (VolatilityRiskContext c : rows) {
            moves.add(c.expectedMoveBps());
            counts.merge(c.riskTier(), 1, Integer::sum);
        }
        moves.sort(Comparator.naturalOrder());
        int elevated = counts.getOrDefault(VolatilityRiskTier.ELEVATED, 0)
                + counts.getOrDefault(VolatilityRiskTier.STRESSED, 0);
        int stressed = counts.getOrDefault(VolatilityRiskTier.STRESSED, 0);
        int median = percentile(moves, 0.50);
        int p90 = percentile(moves, 0.90);
        double elevatedShare = (double) elevated / n;
        double stressedShare = (double) stressed / n;
        return new VolatilityRiskSummary(n, median, p90, elevatedShare, stressedShare, counts,
                "vol_context_summary{n=%d,median_expected_move_bps=%d,p90_expected_move_bps=%d,elevated_or_stressed=%.1f%%,stressed=%.1f%%}"
                        .formatted(n, median, p90, elevatedShare * 100.0, stressedShare * 100.0));
    }

    private static int percentile(List<Integer> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }
}
