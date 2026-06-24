package com.mawai.wiibservice.agent.strategy.monitor.dto;

import java.math.BigDecimal;

/**
 * 日交易网格的一个格子。pnl=当天净盈亏(REALIZED_PNL+COMMISSION+FUNDING_FEE，与钱包变化一致)，
 * tradeCount=当天平仓笔数。date 按东八区(Asia/Shanghai)切日。
 */
public record DailyCell(
        String date,            // yyyy-MM-dd（东八区）
        BigDecimal pnl,         // 当天净盈亏（含手续费）
        int tradeCount) {}      // 当天平仓笔数
