package com.mawai.wiibquant.agent.research.forecast;

import java.util.List;

/** 可插拔预测器。入参 {@link ResearchFeatures} 含决策点"当下及以前"的 bars（point-in-time，绝不含未来）+ 对齐的链下值。 */
public interface Forecaster {

    /**
     * 在单个 walk-forward 训练窗上拟合，返回只可用于随后 test 窗的模型。
     * 默认无状态，保证旧预测器不需要改；有状态实现必须在这里重置/重训，不能累积上一个窗的状态。
     */
    default Forecaster fit(List<TrainingSample> trainSamples) {
        return this;
    }

    Forecast forecast(ResearchFeatures features);

    String name();
}
