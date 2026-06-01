package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.metrics.ReturnSeries;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;

import java.math.BigDecimal;

/**
 * 多策略同框报告里的单策略一行（承接 Slice1 单策略 EvalReport 的策略侧字段）。
 * buy&hold 是市场基准、与策略无关，提到 {@link ComparisonReport} 顶层；这里只放该策略自身的指标与双基准判定。
 */
public record StrategyLine(
        String name,
        RiskAdjustedMetrics metrics,
        BigDecimal strategyReturn,
        boolean beatBuyAndHold,
        double naivePercentile,
        boolean beatNaive,
        ReturnSeries returnSeries
) {
}
