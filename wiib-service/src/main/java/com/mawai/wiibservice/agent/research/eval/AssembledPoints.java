package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibservice.agent.research.forecast.TrainingSample;
import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.math.BigDecimal;
import java.util.List;

/**
 * 逐决策点装配结果（单/多输出评估共用同一份无泄漏口径）。
 * featuresByPoint[i] = 决策点 i 的 point-in-time 特征（绝不含未来）；
 * longBarrierReturnsByPoint[i] = 三隔栏 long 目标；samplesByPoint[i] = (features, longReturn) 训练样本。
 */
record AssembledPoints(
        List<KlineBar> hbars,
        int points,
        List<ResearchFeatures> featuresByPoint,
        List<BigDecimal> longBarrierReturnsByPoint,
        List<TrainingSample> samplesByPoint
) {
}
