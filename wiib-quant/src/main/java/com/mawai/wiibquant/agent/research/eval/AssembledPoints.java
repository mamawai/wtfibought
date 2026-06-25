
package com.mawai.wiibquant.agent.research.eval;

import com.mawai.wiibquant.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibquant.agent.research.forecast.TrainingSample;
import com.mawai.wiibcommon.market.KlineBar;

import java.math.BigDecimal;
import java.util.List;

/**
 * 逐决策点装配结果（单/多输出评估共用同一份无泄漏口径）。
 * decisionBars 是每根 5m/15m 决策 bar，不按 6/12/24h 跳步；
 * featuresByPoint[i] = 决策点 i 的 point-in-time 特征（绝不含未来）；
 * longBarrierReturnsByPoint[i] = 三隔栏 long 目标；
 * pathOutcomesByPoint[i] = 未来 horizon 的 1m 路径验证结果；
 * samplesByPoint[i] = features + 三隔栏收益 + 未来 horizon 原始收益（后两者只给训练窗 fit 用）。
 * baselineSigmaByPoint[i] = 决策当下的 EWMA horizon σ，复用给标签与 vol 基准，避免重复扫回看窗。
 */
record AssembledPoints(
        List<KlineBar> decisionIntervalBars,
        List<KlineBar> decisionBars,
        List<Integer> decisionBarIndexes,
        int horizonDecisionBars,
        long decisionBarMillis,
        int points,
        List<ResearchFeatures> featuresByPoint,
        List<BigDecimal> longBarrierReturnsByPoint,
        List<HorizonPathOutcome> pathOutcomesByPoint,
        List<TrainingSample> samplesByPoint,
        double[] baselineSigmaByPoint
) {
}
