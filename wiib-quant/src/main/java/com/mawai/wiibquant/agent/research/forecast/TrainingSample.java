package com.mawai.wiibquant.agent.research.forecast;

import java.math.BigDecimal;

/**
 * 单个训练样本：features 只能含决策点当下以前的数据；fit 只能拿训练窗样本，不能拿 test 窗。
 * realizedLongReturn 是三隔栏多头收益；realizedForwardReturn 是未来 horizon 原始 log return，专供 vol 校准。
 * realizedRegime 是下一 horizon 未来路径事后 regime 标签，专供 regime 预测腿训练。
 */
public record TrainingSample(ResearchFeatures features, BigDecimal realizedLongReturn,
                             double realizedForwardReturn, MarketRegime realizedRegime) {

    public TrainingSample(ResearchFeatures features, BigDecimal realizedLongReturn) {
        this(features, realizedLongReturn, Double.NaN, null);
    }

    public TrainingSample(ResearchFeatures features, BigDecimal realizedLongReturn, double realizedForwardReturn) {
        this(features, realizedLongReturn, realizedForwardReturn, null);
    }

    public int realizedDirection() {
        return realizedLongReturn == null ? 0 : realizedLongReturn.signum();
    }
}
