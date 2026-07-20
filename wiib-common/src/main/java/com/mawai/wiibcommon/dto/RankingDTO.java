package com.mawai.wiibcommon.dto;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class RankingDTO implements Serializable {
    private Integer rank;
    private Long userId;
    private String username;
    private String avatar;
    private BigDecimal totalAssets;
    private BigDecimal profitPct;
    /** 硬实力盈亏 = 合约净盈亏 + 现货净盈亏(扣优惠券) + 预测已结算净盈亏 */
    private BigDecimal hardcoreProfit;
    /** 优惠券累计省下的金额，独立展示 */
    private BigDecimal buffProfit;
    /** 余额钱包（含冻结）。与游戏钱包一起只是总资产的现金部分，两者相加≠totalAssets */
    private BigDecimal balanceWallet;
    /** 游戏钱包（与全仓风险隔离） */
    private BigDecimal gameWallet;
}
