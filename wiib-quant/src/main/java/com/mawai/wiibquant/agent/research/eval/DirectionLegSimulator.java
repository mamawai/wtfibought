package com.mawai.wiibquant.agent.research.eval;

import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.agent.research.benchmark.BenchmarkCalculator;
import com.mawai.wiibquant.agent.research.metrics.ReturnSeries;
import com.mawai.wiibquant.agent.research.metrics.RiskAdjustedMetrics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 方向腿 walk-forward 模拟的共用累加器——{@link ResearchEvalService} 与 {@link MultiOutputEvalService}
 * 的判决主口径同源于此，改这里等于同时改两边的尺子。
 *
 * <p>逐 test 点 {@link #record}：方向归一化 {-1,0,1} → 逐信号收益（每个非 flat 信号 = 一笔独立往返交易，
 * 扣一次成本）→ 敞口/换手统计 → 非重叠采样（信号持有 horizonDecisionBars 期间不重复开仓）+ 命中统计。
 * 喂完全部点后 {@link #summarize} 出派生判决（复利收益、Sharpe、排列分位；排列检验用 gross——成本对洗牌不变）。
 */
final class DirectionLegSimulator {

    private final BigDecimal costRate;
    private final boolean hasCost;
    private final int horizonDecisionBars;

    private final List<Integer> positions = new ArrayList<>();
    private final List<BigDecimal> posReturns = new ArrayList<>();
    private final List<BigDecimal> testLongReturns = new ArrayList<>();
    // 非重叠口径：重叠口径每 5m 复利等于 horizon/5m 层叠仓敞口，对单倍敞口 buy&hold 不公平——方向判决以非重叠为主
    private final List<BigDecimal> nonOverlapReturns = new ArrayList<>();
    private final List<Integer> nonOverlapPositions = new ArrayList<>();
    private final List<BigDecimal> nonOverlapLongReturns = new ArrayList<>();
    private int nextNonOverlapBarIndex = Integer.MIN_VALUE;
    private int nonOverlapHits;
    private int nonOverlapActive;
    private int nonOverlapActiveHits;
    private int activeCount;
    private int positionChanges;
    private int previousDirection;
    private boolean hasPrevious;

    DirectionLegSimulator(BigDecimal costRate, int horizonDecisionBars) {
        this.costRate = costRate;
        this.hasCost = costRate.signum() > 0;
        this.horizonDecisionBars = horizonDecisionBars;
    }

    /** 记录一个样本外 test 点；decisionBarIndex 用于非重叠采样游标推进。 */
    void record(int rawDirection, BigDecimal longReturn, int decisionBarIndex) {
        // 归一化：只关心多(+1)/空(-1)/平(0)，防越界方向放大收益
        int normDir = Integer.compare(rawDirection, 0);
        positions.add(normDir);
        testLongReturns.add(longReturn);
        BigDecimal posReturn = longReturn.multiply(BigDecimal.valueOf(normDir));
        if (hasCost && normDir != 0) {
            posReturn = posReturn.subtract(costRate);
        }
        posReturns.add(posReturn);

        if (normDir != 0) {
            activeCount++;
        }
        if (hasPrevious && normDir != previousDirection) {
            positionChanges++;
        }
        previousDirection = normDir;
        hasPrevious = true;

        // 非重叠采样：当前 bar 已走出上一信号的持有窗口后才计入
        if (decisionBarIndex >= nextNonOverlapBarIndex) {
            nonOverlapReturns.add(posReturn);
            nonOverlapPositions.add(normDir);
            nonOverlapLongReturns.add(longReturn);
            if (posReturn.signum() > 0) {
                nonOverlapHits++;
            }
            if (normDir != 0) {
                nonOverlapActive++;
                if (posReturn.signum() > 0) {
                    nonOverlapActiveHits++;
                }
            }
            nextNonOverlapBarIndex = decisionBarIndex + horizonDecisionBars;
        }
    }

    List<Integer> positions() {
        return positions;
    }

    /** 派生判决；buy&hold 比较留在调用方（基准与策略无关）。 */
    Verdict summarize(String name, ForecastHorizon horizon, EvalParams params) {
        int totalPoints = positions.size();
        ReturnSeries series = new ReturnSeries(name, posReturns, horizon.periodsPerYear());
        RiskAdjustedMetrics metrics = RiskAdjustedMetrics.from(series);
        double naivePct = positions.isEmpty() ? 0.0
                : BenchmarkCalculator.permutationPercentile(positions, testLongReturns, params.iterations(), params.seed());
        BigDecimal strategyReturn = ResearchEvalService.compound(posReturns);

        BigDecimal nonOverlapReturn = nonOverlapReturns.isEmpty()
                ? BigDecimal.ZERO
                : ResearchEvalService.compound(nonOverlapReturns);
        double nonOverlapSharpe = nonOverlapReturns.isEmpty()
                ? 0.0
                : RiskAdjustedMetrics.from(new ReturnSeries(
                        name + "_non_overlap", nonOverlapReturns, horizon.periodsPerYear()))
                .annualizedSharpe();
        // 非重叠排列分位：重叠口径下 i.i.d. 洗牌会低估持续持仓策略的 null 方差（分位虚高），非重叠近独立、分位才诚实
        double nonOverlapNaivePct = nonOverlapPositions.isEmpty() ? 0.0
                : BenchmarkCalculator.permutationPercentile(
                nonOverlapPositions, nonOverlapLongReturns, params.iterations(), params.seed());

        return new Verdict(series, metrics, strategyReturn, naivePct,
                nonOverlapReturn, nonOverlapSharpe, nonOverlapReturns.size(),
                nonOverlapReturns.isEmpty() ? 0.0 : (double) nonOverlapHits / nonOverlapReturns.size(),
                nonOverlapActive == 0 ? 0.0 : (double) nonOverlapActiveHits / nonOverlapActive,
                nonOverlapNaivePct,
                totalPoints > 0 ? (double) activeCount / totalPoints : 0.0,
                totalPoints > 1 ? (double) positionChanges / (totalPoints - 1) : 0.0);
    }

    /** 方向腿判决摘要；字段口径见 {@link StrategyLine} 同名字段。 */
    record Verdict(
            ReturnSeries series,
            RiskAdjustedMetrics metrics,
            BigDecimal strategyReturn,
            double naivePercentile,
            BigDecimal nonOverlapReturn,
            double nonOverlapSharpe,
            int nonOverlapPeriods,
            double nonOverlapHitRate,
            double nonOverlapActiveHitRate,
            double nonOverlapNaivePercentile,
            double exposure,
            double turnover
    ) {
    }
}
