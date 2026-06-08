package com.mawai.wiibservice.agent.quant.domain;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.VolatilityRiskTier;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record MacroContext(
        String symbol,
        Instant computedAt,
        Map<ForecastHorizon, Leg> legs,
        boolean stale,
        List<String> qualityFlags
) {

    public record Leg(
            VolatilityRiskTier volTier,
            double riskBudgetHint,
            int expectedMoveBps,
            double trailingPercentile,
            com.mawai.wiibservice.agent.research.forecast.MarketRegime regime,
            double regimeConfidence,
            int directionSign,
            double directionConfidence
    ) {
        public static Leg neutral() {
            return new Leg(VolatilityRiskTier.UNKNOWN, 1.0, 0, 0.5,
                    com.mawai.wiibservice.agent.research.forecast.MarketRegime.RANGING,
                    0.0, 0, 0.0);
        }

        public Leg {
            if (volTier == null) {
                volTier = VolatilityRiskTier.UNKNOWN;
            }
            riskBudgetHint = Math.clamp(Double.isFinite(riskBudgetHint) ? riskBudgetHint : 1.0, 0.0, 1.0);
            expectedMoveBps = Math.max(0, expectedMoveBps);
            trailingPercentile = Math.clamp(Double.isFinite(trailingPercentile) ? trailingPercentile : 0.5, 0.0, 1.0);
            if (regime == null) {
                regime = com.mawai.wiibservice.agent.research.forecast.MarketRegime.RANGING;
            }
            regimeConfidence = Math.clamp(Double.isFinite(regimeConfidence) ? regimeConfidence : 0.0, 0.0, 1.0);
            directionSign = Integer.compare(directionSign, 0);
            directionConfidence = Math.clamp(Double.isFinite(directionConfidence) ? directionConfidence : 0.0, 0.0, 1.0);
        }
    }

    public MacroContext {
        symbol = symbol == null || symbol.isBlank() ? "UNKNOWN" : symbol;
        computedAt = computedAt == null ? Instant.EPOCH : computedAt;
        Map<ForecastHorizon, Leg> normalized = new EnumMap<>(ForecastHorizon.class);
        if (legs != null) {
            normalized.putAll(legs);
        }
        for (ForecastHorizon horizon : ForecastHorizon.values()) {
            normalized.putIfAbsent(horizon, Leg.neutral());
        }
        legs = Map.copyOf(normalized);
        qualityFlags = qualityFlags == null ? List.of() : List.copyOf(qualityFlags);
    }

    public static MacroContext neutral(String symbol) {
        return new MacroContext(symbol, Instant.EPOCH, Map.of(), true, List.of("MACRO_CONTEXT_NEUTRAL"));
    }

    public MacroContext withStale(boolean newStale) {
        return new MacroContext(symbol, computedAt, legs, newStale, qualityFlags);
    }

    public MacroRiskHint toRiskHint() {
        double budget = 1.0;
        boolean stressed = false;
        boolean shock = false;
        for (Leg leg : legs.values()) {
            budget = Math.min(budget, leg.riskBudgetHint());
            stressed |= leg.volTier() == VolatilityRiskTier.STRESSED;
            shock |= leg.volTier() == VolatilityRiskTier.STRESSED
                    && leg.regime() == com.mawai.wiibservice.agent.research.forecast.MarketRegime.SHOCK;
        }
        if (stale || qualityFlags.contains("MACRO_CONTEXT_NEUTRAL")) {
            return MacroRiskHint.neutral();
        }
        return new MacroRiskHint(budget, shock, stressed);
    }

    public String toDebateBlock() {
        if (stale || qualityFlags.contains("MACRO_CONTEXT_NEUTRAL")) {
            return "";
        }
        return "research H6/H12/H24 | vol: %s | regime: %s | direction: %s"
                .formatted(volSummary(), regimeSummary(), directionSummary());
    }

    public String toReportBlock() {
        if (stale || qualityFlags.contains("MACRO_CONTEXT_NEUTRAL")) {
            return "宏观上下文预热中，暂不介入交易。";
        }
        return "research H6/H12/H24 | vol: %s | regime: %s | direction: %s"
                .formatted(volSummary(), regimeSummary(), directionSummary());
    }

    private String volSummary() {
        return "H6=%s(%dbps,预算%.2f)/H12=%s(%dbps,预算%.2f)/H24=%s(%dbps,预算%.2f)"
                .formatted(
                        leg(ForecastHorizon.H6).volTier(), leg(ForecastHorizon.H6).expectedMoveBps(), leg(ForecastHorizon.H6).riskBudgetHint(),
                        leg(ForecastHorizon.H12).volTier(), leg(ForecastHorizon.H12).expectedMoveBps(), leg(ForecastHorizon.H12).riskBudgetHint(),
                        leg(ForecastHorizon.H24).volTier(), leg(ForecastHorizon.H24).expectedMoveBps(), leg(ForecastHorizon.H24).riskBudgetHint());
    }

    private String regimeSummary() {
        return "H6=%s@%.2f/H12=%s@%.2f/H24=%s@%.2f"
                .formatted(
                        leg(ForecastHorizon.H6).regime(), leg(ForecastHorizon.H6).regimeConfidence(),
                        leg(ForecastHorizon.H12).regime(), leg(ForecastHorizon.H12).regimeConfidence(),
                        leg(ForecastHorizon.H24).regime(), leg(ForecastHorizon.H24).regimeConfidence());
    }

    private String directionSummary() {
        return "H6=%s@%.2f/H12=%s@%.2f/H24=%s@%.2f(仅报告建议)"
                .formatted(
                        directionText(leg(ForecastHorizon.H6).directionSign()), leg(ForecastHorizon.H6).directionConfidence(),
                        directionText(leg(ForecastHorizon.H12).directionSign()), leg(ForecastHorizon.H12).directionConfidence(),
                        directionText(leg(ForecastHorizon.H24).directionSign()), leg(ForecastHorizon.H24).directionConfidence());
    }

    private Leg leg(ForecastHorizon horizon) {
        return legs.getOrDefault(horizon, Leg.neutral());
    }

    private static String directionText(int sign) {
        return sign > 0 ? "LONG" : sign < 0 ? "SHORT" : "FLAT";
    }
}
