package com.mawai.wiibservice.agent.binance.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * GET /fapi/v1/userTrades 账户成交明细（每一笔真实 fill）。
 *
 * <p>看板交易记录 + fill 对账的权威来源：maker 标志验证零滑点、commission 是真实成本、
 * realizedPnl 是该笔已实现盈亏（不含手续费）。数值字段 Binance 返回字符串，fastjson2 自动转 BigDecimal。</p>
 */
@Data
public class UserTrade {
    /** 成交ID（同 symbol 内递增，可做增量拉取游标 fromId） */
    private Long id;
    /** 关联订单ID（对应进场/平仓单，可回溯 clientOrderId=FIBO-...） */
    private Long orderId;
    private String symbol;
    /** BUY / SELL */
    private String side;
    /** BOTH(单向) / LONG / SHORT(双向) */
    private String positionSide;
    /** 成交价 */
    private BigDecimal price;
    /** 成交数量 */
    private BigDecimal qty;
    /** 成交额 = price × qty */
    private BigDecimal quoteQty;
    /** 该笔已实现盈亏（不含手续费；开仓笔为0，平仓笔体现盈亏） */
    private BigDecimal realizedPnl;
    /** 手续费（负数=支出） */
    private BigDecimal commission;
    private String commissionAsset;
    /** true=maker（挂单被动成交，GTX 下成交价=挂单价，零滑点） */
    private Boolean maker;
    /** true=买方 */
    private Boolean buyer;
    /** 成交时刻（毫秒） */
    private Long time;
}
