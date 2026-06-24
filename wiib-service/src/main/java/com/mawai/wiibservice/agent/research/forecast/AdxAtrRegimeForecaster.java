package com.mawai.wiibservice.agent.research.forecast;

/**
 * 旧 regime 腿包装：按当前 ADX/ATR 状态分类。
 * 它回答“现在像什么”，不是训练出的 future-regime 预测；保留作基准。
 */
public final class AdxAtrRegimeForecaster implements RegimeForecaster {

    @Override
    public RegimeVerdict forecastRegime(ResearchFeatures features) {
        return RegimeClassifier.classify(IndicatorAdapter.researchIndicators(features == null ? null : features.barsUpToNow()));
    }

    @Override
    public String name() {
        return "adx_atr_current";
    }
}
