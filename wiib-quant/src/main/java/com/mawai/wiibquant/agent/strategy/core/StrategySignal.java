package com.mawai.wiibquant.agent.strategy.core;

import java.math.BigDecimal;

/**
 * 策略信号 = 完整交易计划。SL/TP 用绝对价（fibo 等结构性策略的位是绝对的，
 * 成交价滑到下一根开盘后 SL/TP 不跟着漂移）。
 */
public record StrategySignal(
        String strategyId,
        String symbol,
        String side,              // LONG / SHORT
        boolean isLong,
        BigDecimal entryRefPrice, // MARKET=确认bar收盘参考价；LIMIT=挂单价（在此价位挂限价单）
        BigDecimal stopLossPrice,
        BigDecimal takeProfitPrice,
        double score,
        String reason,
        long barCloseTime,
        String orderType) {       // MARKET=下一根开盘市价成交；LIMIT=挂限价单等回踩触发（maker）

    /** 兼容构造：不带 orderType 默认市价单，老调用走这里，行为不变。 */
    public StrategySignal(String strategyId, String symbol, String side, boolean isLong,
                          BigDecimal entryRefPrice, BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                          double score, String reason, long barCloseTime) {
        this(strategyId, symbol, side, isLong, entryRefPrice, stopLossPrice, takeProfitPrice,
                score, reason, barCloseTime, "MARKET");
    }
}
