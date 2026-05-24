package com.mawai.wiibservice.agent.binance.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * GET /fapi/v3/positionRisk 响应（每条一个 PositionRisk，返回数组）。
 * 注意：v3 已移除 leverage / marginType / isAutoAddMargin 字段，
 * 这些需要从 account 接口或单独的 leverage 设置接口获取。
 */
@Data
public class PositionRisk {

    private String symbol;
    /** BOTH(单向) / LONG / SHORT(双向) */
    private String positionSide;
    /** 持仓数量（负数=空头） */
    private BigDecimal positionAmt;
    /** 开仓均价 */
    private BigDecimal entryPrice;
    /** 盈亏平衡价（含手续费） */
    private BigDecimal breakEvenPrice;
    /** 当前标记价 */
    private BigDecimal markPrice;
    private BigDecimal unRealizedProfit;
    /** 预估强平价，0 表示无持仓 */
    private BigDecimal liquidationPrice;
    /** 逐仓保证金 */
    private BigDecimal isolatedMargin;
    /** 名义价值（带符号） */
    private BigDecimal notional;
    /** 保证金币种，通常 USDT */
    private String marginAsset;
    /** 逐仓钱包余额 */
    private BigDecimal isolatedWallet;
    private BigDecimal initialMargin;
    private BigDecimal maintMargin;
    private BigDecimal positionInitialMargin;
    private BigDecimal openOrderInitialMargin;
    /** ADL 强平队列排名 */
    private Integer adl;
    /** 当前挂买单名义价值 */
    private BigDecimal bidNotional;
    /** 当前挂卖单名义价值 */
    private BigDecimal askNotional;
    private Long updateTime;
}
