package com.mawai.wiibquant.agent.research.forecast;

/** 预测结果：direction ∈ {+1 多, 0 空仓, −1 空}，confidence ∈ [0,1]。 */
public record Forecast(int direction, double confidence) {

    public static Forecast flat() {
        return new Forecast(0, 0.0);
    }
}
