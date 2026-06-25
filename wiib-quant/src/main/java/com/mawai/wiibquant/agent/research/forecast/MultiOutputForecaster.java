package com.mawai.wiibquant.agent.research.forecast;

import java.util.List;

/**
 * 多输出预测器：一次产 vol/regime/direction 三输出。与单输出 {@link Forecaster} 并存——不替换它，
 * 既有 5 个 forecaster 与 ResearchEvalService 尺子不受影响（向后兼容）。
 */
public interface MultiOutputForecaster {

    /** 同 {@link Forecaster#fit}：单个 walk-forward 训练窗上拟合，返回只用于随后 test 窗的模型。 */
    default MultiOutputForecaster fit(List<TrainingSample> trainSamples) {
        return this;
    }

    MultiOutputForecast forecast(ResearchFeatures features);

    String name();

    /**
     * 方向腿视图：把本预测器适配成单输出 {@link Forecaster}，使其方向腿能直接跑进现有方向尺子
     * （ResearchEvalService.evaluateBars 吃 List&lt;Forecaster&gt;）——可测量 by construction。
     */
    default Forecaster asDirectionForecaster() {
        MultiOutputForecaster self = this;
        return new Forecaster() {
            @Override
            public Forecaster fit(List<TrainingSample> trainSamples) {
                return self.fit(trainSamples).asDirectionForecaster(); // 训练转发到本预测器，再包成方向腿
            }

            @Override
            public Forecast forecast(ResearchFeatures features) {
                return self.forecast(features).direction();            // 只取方向腿，喂方向尺子
            }

            @Override
            public String name() {
                return self.name() + "#dir";
            }
        };
    }
}
