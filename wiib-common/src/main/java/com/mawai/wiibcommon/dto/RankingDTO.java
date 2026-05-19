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
}
