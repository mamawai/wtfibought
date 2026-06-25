package com.mawai.wiibquant.agent.binance.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * GET /fapi/v1/income 损益流水（账户资金变动逐条记录）。
 *
 * <p>权益曲线 + 日盈亏聚合的权威来源。日净盈亏 = 当天 sum(REALIZED_PNL + COMMISSION + FUNDING_FEE)，
 * 等于币安钱包当天净变化（realizedPnl 不含手续费，COMMISSION 单独成条，必须合并才等于真实盈亏）。</p>
 */
@Data
public class IncomeRecord {
    private String symbol;
    /** REALIZED_PNL / COMMISSION / FUNDING_FEE / TRANSFER / WELCOME_BONUS ... */
    private String incomeType;
    /** 金额（带符号：盈利/转入为正，手续费/亏损为负） */
    private BigDecimal income;
    private String asset;
    /** 附加说明（不同 incomeType 含义不同） */
    private String info;
    /** 发生时刻（毫秒） */
    private Long time;
    private Long tranId;
    /** 关联成交ID（部分类型有，可与 userTrades 对账） */
    private String tradeId;
}
