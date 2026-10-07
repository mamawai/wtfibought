package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("crypto_order")
public class CryptoOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String symbol;

    /** BUY/SELL */
    private String orderSide;

    /** MARKET/LIMIT */
    private String orderType;

    /** 委托数量（支持小数，如0.001 BTC） */
    private BigDecimal quantity;

    /** 杠杆倍数 1-10 */
    private Integer leverage;

    private BigDecimal limitPrice;

    /** 限价买单冻结资金 */
    private BigDecimal frozenAmount;

    private BigDecimal filledPrice;

    private BigDecimal filledAmount;

    private BigDecimal commission;

    private BigDecimal triggerPrice;

    private LocalDateTime triggeredAt;

    /** PENDING/TRIGGERED/FILLED/CANCELLED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
