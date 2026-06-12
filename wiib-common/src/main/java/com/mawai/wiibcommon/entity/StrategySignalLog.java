package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 策略运行时信号记录；用于实盘信号复盘和确认腿归因。 */
@Data
@TableName("strategy_signal")
public class StrategySignalLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String strategyId;

    private String symbol;

    private String side;

    private String mode;

    private BigDecimal entryRefPrice;

    private BigDecimal stopLoss;

    private BigDecimal takeProfit;

    private BigDecimal score;

    private String reason;

    private String legTags;

    private Long barCloseTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
