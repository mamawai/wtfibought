package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("prediction_bet")
public class PredictionBet {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long roundId;

    private Long windowStart;

    /** UP/DOWN */
    private String side;

    private BigDecimal contracts;

    private BigDecimal cost;

    /** 买入手续费，cost 之外另扣；部分卖出时按份数拆到卖出那行 */
    private BigDecimal buyFee;

    private BigDecimal avgPrice;

    private BigDecimal payout;

    /** ACTIVE/WON/LOST/DRAW/SOLD/CANCELLED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
