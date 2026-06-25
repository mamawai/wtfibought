package com.mawai.wiibquant.agent.research.forecast;

import java.util.List;

/** 波动率预测腿：输入 point-in-time 特征，输出下一 horizon 的 sigma。 */
public interface VolForecaster {

    default VolForecaster fit(List<TrainingSample> trainSamples) {
        return this;
    }

    double forecastSigma(ResearchFeatures features);

    String name();
}
