package com.mawai.wiibquant.agent.research.metrics;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次试验的逐期收益序列。这是 Deflated Sharpe / PBO 的"现在就埋、计算后置"钩子：
 * 后续 slice 试多组配置时，把每次的 ReturnSeries 收集起来即可算 DSR/PBO。
 */
public record ReturnSeries(
        String label,
        List<BigDecimal> periodReturns,
        int periodsPerYear
) {
}
