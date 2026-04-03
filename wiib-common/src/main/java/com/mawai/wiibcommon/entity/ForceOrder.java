package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("force_order")
public class ForceOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;

    /** SELL=多头被强平, BUY=空头被强平 */
    private String side;

    private BigDecimal price;

    private BigDecimal avgPrice;

    private BigDecimal quantity;

    /** price * quantity */
    private BigDecimal amount;

    /** FILLED / NEW / ... */
    private String status;

    private LocalDateTime tradeTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
