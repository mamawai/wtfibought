package com.mawai.wiibservice.agent.trading.entry.model;

import java.math.BigDecimal;

public record EntryStrategyCandidate(
        String path,
        String label,
        String side,
        boolean isLong,
        double score,
        BigDecimal slDistance,
        BigDecimal tpDistance,
        int maxLeverage,
        double positionScale,
        String reason,
        String entryMode,
        boolean wasLateContinuation
) {
    public EntryStrategyCandidate(String path,
                                  String label,
                                  String side,
                                  boolean isLong,
                                  double score,
                                  BigDecimal slDistance,
                                  BigDecimal tpDistance,
                                  int maxLeverage,
                                  double positionScale,
                                  String reason) {
        this(path, label, side, isLong, score, slDistance, tpDistance, maxLeverage,
                positionScale, reason, null, false);
    }
}
