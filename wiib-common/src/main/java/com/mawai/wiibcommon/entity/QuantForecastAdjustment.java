package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 轻周期对父重周期的修正记录。
 * 一次轻周期可能对同一父重周期的多个 horizon 产生修正（0-3 条）。
 */
@Data
@TableName("quant_forecast_adjustment")
public class QuantForecastAdjustment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String lightCycleId;

    private String heavyCycleId;

    private String symbol;

    /** 轻周期发出修正的 horizon（0_10/10_20/20_30） */
    private String lightHorizon;

    /** 被修正的重周期 horizon（按 dMin 映射后落在哪段） */
    private String heavyHorizon;

    /** SAME_DIR_BOOST / OPPO_WEAK_PENALTY / OPPO_STRONG_PENALTY / FLIP */
    private String adjustType;

    private String lightDirection;

    private BigDecimal lightConfidence;

    private String prevHeavyDirection;

    private BigDecimal prevHeavyConfidence;

    private String newHeavyDirection;

    private BigDecimal newHeavyConfidence;

    /** 本次写入后该 heavyHorizon 的连续反转票数（翻盘时清零回 0） */
    private Integer voteCountAfter;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
