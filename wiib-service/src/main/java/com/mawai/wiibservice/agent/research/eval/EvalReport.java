package com.mawai.wiibservice.agent.research.eval;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibservice.agent.research.metrics.ReturnSeries;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;

import java.math.BigDecimal;

/** 样本外评估报告：策略 vs buy&hold vs naive 三线可比（spec §6 验收）。 */
public record EvalReport(
        String symbol,
        int horizonHours,
        int totalHbars,
        int testPoints,
        RiskAdjustedMetrics metrics,
        BigDecimal buyAndHoldReturn,
        BigDecimal strategyReturn,
        double naivePercentile,
        boolean beatBuyAndHold,
        boolean beatNaive,
        ReturnSeries returnSeries
) {
    public String toJson() {
        return JSON.toJSONString(this);
    }

    /** 人眼可比的三线摘要。 */
    public String summary() {
        return String.format(
                "[%s %dh] 样本外 %d 点 | 策略: 收益=%.4f 年化Sharpe=%.2f Calmar=%.2f MaxDD=%.2f%% 命中=%.1f%% "
                        + "| buy&hold=%.4f(%s) | naive分位=%.1f%%(%s)",
                symbol, horizonHours, testPoints,
                strategyReturn.doubleValue(), metrics.annualizedSharpe(), metrics.calmar(),
                metrics.maxDrawdown() * 100, metrics.hitRate() * 100,
                buyAndHoldReturn.doubleValue(), beatBuyAndHold ? "跑赢" : "跑输",
                naivePercentile * 100, beatNaive ? "显著" : "不显著");
    }
}
