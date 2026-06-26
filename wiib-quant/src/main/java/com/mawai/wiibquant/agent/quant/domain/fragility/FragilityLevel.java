package com.mawai.wiibquant.agent.quant.domain.fragility;

/** 市场脆弱度分级。分档阈值待 Step 6 用 research 框架离线校准。 */
public enum FragilityLevel {
    LOW("平稳"),
    ELEVATED("偏脆"),
    HIGH("脆弱"),
    EXTREME("极脆");

    private final String cn;

    FragilityLevel(String cn) {
        this.cn = cn;
    }

    public String cn() {
        return cn;
    }

    /** score(0-100) → 分级。 */
    public static FragilityLevel of(int score) {
        if (score >= 75) {
            return EXTREME;
        }
        if (score >= 50) {
            return HIGH;
        }
        if (score >= 25) {
            return ELEVATED;
        }
        return LOW;
    }
}
