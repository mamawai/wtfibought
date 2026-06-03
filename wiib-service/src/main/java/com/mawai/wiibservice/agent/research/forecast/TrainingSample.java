package com.mawai.wiibservice.agent.research.forecast;

import java.math.BigDecimal;

/**
 * 单个训练样本：features 只能含决策点当下以前的数据；realizedLongReturn 是该点事后按三隔栏得到的多头收益。
 * fit 只能拿训练窗样本，不能拿 test 窗，避免把未来标签混进预测器。
 */
public record TrainingSample(ResearchFeatures features, BigDecimal realizedLongReturn) {

    public int realizedDirection() {
        return realizedLongReturn == null ? 0 : realizedLongReturn.signum();
    }
}
