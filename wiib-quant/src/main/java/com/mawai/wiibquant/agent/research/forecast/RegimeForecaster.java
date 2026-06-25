package com.mawai.wiibquant.agent.research.forecast;

import java.util.List;

/** regime 预测腿：输入 point-in-time 特征，输出下一 horizon 的 regime 判断。 */
public interface RegimeForecaster {

    default RegimeForecaster fit(List<TrainingSample> trainSamples) {
        return this;
    }

    RegimeVerdict forecastRegime(ResearchFeatures features);

    String name();
}
