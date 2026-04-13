package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_trading_decision")
public class AiTradingDecision {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer cycleNo;

    private String symbol;

    private String action;

    private String reasoning;

    private String marketContext;

    private String positionSnapshot;

    private String executionResult;

    private BigDecimal balanceBefore;

    private BigDecimal balanceAfter;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
