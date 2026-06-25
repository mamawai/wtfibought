package com.mawai.wiibquant.agent.strategy.monitor.dto;

import java.math.BigDecimal;

/**
 * 权益曲线的一个点：cumPnl = 截至该时刻的累计已实现盈亏(从0起，含手续费)。
 * 直接反映"策略到底赚不赚钱"，不掺本金转账(TRANSFER/WELCOME_BONUS 已剔除)。
 */
public record EquityPoint(
        long time,              // 毫秒时刻
        BigDecimal cumPnl) {}   // 累计已实现盈亏
