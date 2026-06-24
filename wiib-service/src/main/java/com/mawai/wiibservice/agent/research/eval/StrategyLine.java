package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.metrics.ReturnSeries;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;

import java.math.BigDecimal;

/**
 * 多策略同框报告里的单策略一行（承接 Slice1 单策略 EvalReport 的策略侧字段）。
 * buy&hold 是市场基准、与策略无关，提到 {@link ComparisonReport} 顶层；这里只放该策略自身的指标与双基准判定。
 *
 * <p>双口径收益：
 * <ul>
 *   <li>{@link #strategyReturn} / {@link #metrics} — 重叠口径（每决策 bar 都出信号，horizon 内信号叠加），
 *       复利放大后容易虚高；保留为诊断参考。</li>
 *   <li>{@link #nonOverlapReturn} / {@link #nonOverlapSharpe} / {@link #nonOverlapNaivePercentile}
 *       — 非重叠方向评估口径（horizon 内不重复取样），策略筛选以此为主。</li>
 * </ul>
 */
public record StrategyLine(
        String name,
        RiskAdjustedMetrics metrics,
        BigDecimal strategyReturn,
        boolean beatBuyAndHold,
        double naivePercentile,
        boolean beatNaive,
        ReturnSeries returnSeries,
        // ---- 非重叠口径（实盘可执行） ----
        BigDecimal nonOverlapReturn,
        double nonOverlapSharpe,
        int nonOverlapPeriods,
        double nonOverlapHitRate,
        double nonOverlapActiveHitRate,
        boolean beatBuyAndHoldByNonOverlap,
        double nonOverlapNaivePercentile,
        boolean beatNonOverlapNaive,
        /** 持仓占比：非 flat 点数 / 总点数 (0~1) */
        double exposure,
        /** 换手率：方向变化次数 / (总点数-1) (0~1) */
        double turnover
) {
}
