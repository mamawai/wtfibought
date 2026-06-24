package com.mawai.wiibservice.agent.research.eval;

import com.alibaba.fastjson2.JSON;

import java.math.BigDecimal;
import java.util.List;

/**
 * 多策略同框样本外报告（取代 Slice1 单策略 EvalReport 作顶层产出）：共享窗口 + buy&hold 基准算一次，每策略一条 {@link StrategyLine}。
 * 满足 spec §6 "四线同框"：buy&hold 一行 + N 条策略行（各含 vs buy&hold / naive 判定）。
 */
public record ComparisonReport(
        String symbol,
        int horizonHours,
        int decisionBarMinutes,
        int totalDecisionBars,
        int testPoints,
        BigDecimal buyAndHoldReturn,
        List<StrategyLine> strategies
) {
    public String toJson() {
        return JSON.toJSONString(this);
    }

    /** 人眼可比的多线摘要：buy&hold 基准一行 + 每策略一行（双口径：重叠/非重叠）。 */
    public String summary() {
        StringBuilder sb = new StringBuilder(String.format(
                "[%s %dm→%dh] 样本外 %d 点 | buy&hold=%.4f",
                symbol, decisionBarMinutes, horizonHours, testPoints, buyAndHoldReturn.doubleValue()));
        for (StrategyLine s : strategies) {
            sb.append(String.format(
                    " || %s: 非重叠收益=%.4f 非重叠Sharpe=%.2f 命中=%.1f%% active命中=%.1f%% 非重叠naive=%.1f%%(%s) vs B&H=%s 敞口=%.0f%% 换手=%.0f%% | 重叠诊断收益=%.4f Sharpe=%.2f naive=%.1f%%",
                    s.name(),
                    s.nonOverlapReturn().doubleValue(), s.nonOverlapSharpe(),
                    s.nonOverlapHitRate() * 100, s.nonOverlapActiveHitRate() * 100,
                    s.nonOverlapNaivePercentile() * 100,
                    s.beatNonOverlapNaive() ? "显著" : "不显著",
                    s.beatBuyAndHoldByNonOverlap() ? "跑赢" : "跑输",
                    s.exposure() * 100, s.turnover() * 100,
                    s.strategyReturn().doubleValue(), s.metrics().annualizedSharpe(),
                    s.naivePercentile() * 100));
        }
        return sb.toString();
    }
}
