package com.mawai.wiibservice.agent.trading;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

public final class ExitPlanFactory {

    private static final Duration FAST_TIME_LIMIT = Duration.ofMinutes(30);
    private static final Duration TREND_TIME_LIMIT = Duration.ofMinutes(90);

    private ExitPlanFactory() {
    }

    static ExitPath mapPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return ExitPath.TREND;
        }
        return switch (rawPath.trim().toUpperCase(Locale.ROOT)) {
            case "BREAKOUT" -> ExitPath.BREAKOUT;
            case "MR", "MEAN_REVERSION" -> ExitPath.MR;
            case "TREND", "LEGACY_TREND" -> ExitPath.TREND;
            default -> ExitPath.TREND;
        };
    }

    static Duration timeLimitFor(ExitPath path) {
        return path == ExitPath.TREND ? TREND_TIME_LIMIT : FAST_TIME_LIMIT;
    }

    public static ExitPlan fromEntry(String rawPath,
                              String side,
                              BigDecimal entryPrice,
                              BigDecimal initialSL,
                              BigDecimal atrAtEntry,
                              Double entryBollPb,
                              Double entryRsi,
                              Integer entryMa1h,
                              Integer entryMa15m,
                              LocalDateTime createdAt) {
        ExitPath path = mapPath(rawPath);
        BigDecimal riskPerUnit = calcRiskPerUnit(entryPrice, initialSL);
        return new ExitPlan(
                path, side, entryPrice, initialSL, riskPerUnit, timeLimitFor(path), createdAt, atrAtEntry,
                entryBollPb, entryRsi, entryMa1h, entryMa15m,
                false, Set.of(), false, 0.0, false);
    }

    public static ExitPlan recovered(String rawPath,
                              String side,
                              BigDecimal entryPrice,
                              BigDecimal initialSL,
                              BigDecimal riskPerUnit,
                              BigDecimal atrAtEntry,
                              boolean breakevenDone,
                              LocalDateTime createdAt) {
        ExitPath path = mapPath(rawPath);
        requirePositive(entryPrice, "entryPrice");
        requirePositive(riskPerUnit, "riskPerUnit");
        return new ExitPlan(
                path, side, entryPrice, initialSL, riskPerUnit, timeLimitFor(path), createdAt, atrAtEntry,
                null, null, null, null,
                breakevenDone, Set.of(), false, 0.0, true);
    }

    static BigDecimal calcRiskPerUnit(BigDecimal entryPrice, BigDecimal initialSL) {
        requirePositive(entryPrice, "entryPrice");
        requirePositive(initialSL, "initialSL");
        BigDecimal riskPerUnit = entryPrice.subtract(initialSL).abs();
        requirePositive(riskPerUnit, "riskPerUnit");
        return riskPerUnit;
    }

    private static void requirePositive(BigDecimal value, String fieldName) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
