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

    private BigDecimal avgPrice;

    private BigDecimal payout;

    /** ACTIVE/WON/LOST/DRAW/SOLD/CANCELLED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
