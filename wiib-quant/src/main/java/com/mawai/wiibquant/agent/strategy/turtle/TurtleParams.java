package com.mawai.wiibquant.agent.strategy.turtle;

/**
 * TURTLE 多空趋势突破参数组。
 *
 * <p>所有判断只消费已闭合决策桶。入场通道排除当前桶，避免当前高低价污染历史极值；
 * 灾难止损在信号产生时按 ATR 固定，主要盈利出口是反向退出通道，不设置固定 TP。</p>
 */
public record TurtleParams(
        long decisionTfMillis,
        int entryLookback,
        int exitLookback,
        int atrPeriod,
        double stopAtrMult,
        boolean stopEntry     // false=决策桶收盘确认后下根开盘市价入场；true=盘中触价单在通道价直接成交
) {

    public TurtleParams {
        if (decisionTfMillis <= 0) throw new IllegalArgumentException("decisionTfMillis 必须为正数");
        if (entryLookback < 2) throw new IllegalArgumentException("entryLookback 至少为2");
        if (exitLookback < 2 || exitLookback >= entryLookback) {
            throw new IllegalArgumentException("exitLookback 必须不少于2且小于 entryLookback");
        }
        if (atrPeriod < 2) throw new IllegalArgumentException("atrPeriod 至少为2");
        if (!(stopAtrMult > 0)) throw new IllegalArgumentException("stopAtrMult 必须为正数");
    }

    /**
     * 4H 90/15 触价入场：六币(BTC/ETH/SOL/DOGE/XRP/BNB) 2021~2026 粗+细网格双模式验证的高原中心，
     * 触价模式回测收益约为收盘确认的1.8倍（见 TurtleSweepDbRun）。此即 live 部署形态。
     */
    public static TurtleParams defaults() {
        return new TurtleParams(4 * 3_600_000L, 90, 15, 20, 2.0, true);
    }
}
