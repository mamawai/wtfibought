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

    /** 人眼可比的多线摘要：buy&hold 基准一行 + 每策略一行。 */
    public String summary() {
        StringBuilder sb = new StringBuilder(String.format(
                "[%s %dm→%dh] 样本外 %d 点 | buy&hold=%.4f",
                symbol, decisionBarMinutes, horizonHours, testPoints, buyAndHoldReturn.doubleValue()));
        for (StrategyLine s : strategies) {
            sb.append(String.format(
                    " || %s: 收益=%.4f 年化Sharpe=%.2f Calmar=%.2f MaxDD=%.2f%% 命中=%.1f%% vs B&H=%s naive分位=%.1f%%(%s)",
                    s.name(), s.strategyReturn().doubleValue(), s.metrics().annualizedSharpe(),
                    s.metrics().calmar(), s.metrics().maxDrawdown() * 100, s.metrics().hitRate() * 100,
                    s.beatBuyAndHold() ? "跑赢" : "跑输",
                    s.naivePercentile() * 100, s.beatNaive() ? "显著" : "不显著"));
        }
        return sb.toString();
    }
}
