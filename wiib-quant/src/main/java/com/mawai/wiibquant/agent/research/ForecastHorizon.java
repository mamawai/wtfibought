package com.mawai.wiibquant.agent.research;

/** 预测周期：本刀只评估 6/12/24h 三档。 */
public enum ForecastHorizon {
    H6(6), H12(12), H24(24);

    private final int hours;

    ForecastHorizon(int hours) {
        this.hours = hours;
    }

    public int hours() {
        return hours;
    }

    public long millis() {
        return hours * 3_600_000L;
    }

    /** 一年有多少个该周期：8760 小时 / H。H6=1460, H12=730, H24=365。 */
    public int periodsPerYear() {
        return 8760 / hours;
    }

    public static ForecastHorizon fromHours(int hours) {
        for (ForecastHorizon h : values()) {
            if (h.hours == hours) return h;
        }
        throw new IllegalArgumentException("不支持的周期(仅 6/12/24): " + hours);
    }
}
