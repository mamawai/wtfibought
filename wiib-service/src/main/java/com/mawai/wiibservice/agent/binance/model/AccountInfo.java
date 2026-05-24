package com.mawai.wiibservice.agent.binance.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /fapi/v3/account 响应。
 * 数值字段在 Binance 响应里都是字符串，fastjson2 会自动转 BigDecimal。
 */
@Data
public class AccountInfo {

    /** 起始保证金总额 */
    private BigDecimal totalInitialMargin;
    /** 维持保证金总额 */
    private BigDecimal totalMaintMargin;
    /** 账户总余额 */
    private BigDecimal totalWalletBalance;
    /** 持仓未实现盈亏总额 */
    private BigDecimal totalUnrealizedProfit;
    /** 保证金总余额（钱包+未实现盈亏） */
    private BigDecimal totalMarginBalance;
    /** 持仓起始保证金 */
    private BigDecimal totalPositionInitialMargin;
    /** 挂单起始保证金 */
    private BigDecimal totalOpenOrderInitialMargin;
    /** 全仓账户余额 */
    private BigDecimal totalCrossWalletBalance;
    /** 全仓未实现盈亏 */
    private BigDecimal totalCrossUnPnl;
    /** 可用余额（最常用） */
    private BigDecimal availableBalance;
    /** 最大可转出余额 */
    private BigDecimal maxWithdrawAmount;

    private List<Asset> assets;
    private List<Position> positions;

    @Data
    public static class Asset {
        private String asset;
        private BigDecimal walletBalance;
        private BigDecimal unrealizedProfit;
        private BigDecimal marginBalance;
        private BigDecimal maintMargin;
        private BigDecimal initialMargin;
        private BigDecimal positionInitialMargin;
        private BigDecimal openOrderInitialMargin;
        private BigDecimal crossWalletBalance;
        private BigDecimal crossUnPnl;
        private BigDecimal availableBalance;
        private BigDecimal maxWithdrawAmount;
        private Long updateTime;
    }

    /**
     * 账户接口返回的精简持仓视图。
     * 完整持仓信息（含 entryPrice/markPrice/liquidationPrice）走 {@link PositionRisk}。
     */
    @Data
    public static class Position {
        private String symbol;
        /** BOTH(单向) / LONG / SHORT(双向) */
        private String positionSide;
        /** 持仓数量（负数=空头） */
        private BigDecimal positionAmt;
        private BigDecimal unrealizedProfit;
        private BigDecimal isolatedMargin;
        /** 名义价值（带符号） */
        private BigDecimal notional;
        private BigDecimal isolatedWallet;
        private BigDecimal initialMargin;
        private BigDecimal maintMargin;
        private Long updateTime;
    }
}
