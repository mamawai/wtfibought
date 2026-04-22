package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mawai.wiibcommon.handler.JsonbStringTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName(value = "phase0a_daily_stats", autoResultMap = true)
public class Phase0aDailyStats {

    @TableId(type = IdType.INPUT)
    private LocalDate statDate;

    private Long userId;

    private Integer totalOpens;
    private Integer opensBtc;
    private Integer opensEth;
    private Integer totalCloses;
    private Integer positionsClosed;

    private Integer wins;
    private Integer losses;
    private BigDecimal winRate;

    private BigDecimal realizedPnl;
    private BigDecimal avgWin;
    private BigDecimal avgLoss;
    private BigDecimal pnlRatio;

    private BigDecimal equityStart;
    private BigDecimal equityEnd;
    private BigDecimal equityHigh;
    private BigDecimal equityLow;
    private BigDecimal dailyDrawdownPct;
    private BigDecimal dailyReturnPct;

    private BigDecimal avgHoldingMinutes;
    private Integer dailyLossBlocks;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String strategyBreakdown;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
