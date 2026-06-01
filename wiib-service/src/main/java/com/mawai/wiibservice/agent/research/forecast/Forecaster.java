package com.mawai.wiibservice.agent.research.forecast;

/** 可插拔预测器。入参 {@link ResearchFeatures} 含决策点"当下及以前"的 bars（point-in-time，绝不含未来）+ 对齐的链下值。 */
public interface Forecaster {

    Forecast forecast(ResearchFeatures features);

    String name();
}
