package com.mawai.wiibquant.agent.strategy.core;

import java.math.BigDecimal;

/**
 * 策略信号 = 完整交易计划。SL/TP 用绝对价（fibo 等结构性策略的位是绝对的，
 * 成交价滑到下一根开盘后 SL/TP 不跟着漂移）。TP 可为空：趋势跟随策略可只挂灾难止损，
 * 再通过持仓期回调按通道/时间等结构退出。
 */
public record StrategySignal(
        String strategyId,
        String symbol,
        String side,              // LONG / SHORT
        boolean isLong,
        BigDecimal entryRefPrice, // MARKET=确认bar收盘参考价；LIMIT=挂单价；STOP=触发价（突破才成交）
        BigDecimal stopLossPrice,
        BigDecimal takeProfitPrice,
        double score,
        String reason,
        long barCloseTime,
        String orderType) {       // MARKET=下一根开盘市价成交；LIMIT=挂限价单等回踩触发（maker）；STOP=触价单等突破触发（taker）
}
