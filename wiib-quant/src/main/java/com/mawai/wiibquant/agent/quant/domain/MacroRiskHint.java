package com.mawai.wiibquant.agent.quant.domain;

public record MacroRiskHint(double budgetMultiplier, boolean macroShock, boolean macroStressed) {

    public static MacroRiskHint neutral() {
        return new MacroRiskHint(1.0, false, false);
    }

    public MacroRiskHint {
        if (!Double.isFinite(budgetMultiplier)) {
            budgetMultiplier = 1.0;
        }
        budgetMultiplier = Math.clamp(budgetMultiplier, 0.0, 1.0);
    }

    public boolean neutralHint() {
        return !macroShock && !macroStressed && Math.abs(budgetMultiplier - 1.0) < 1e-9;
    }
}
