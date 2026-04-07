package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mawai.wiibcommon.handler.JsonbStringTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "quant_forecast_cycle", autoResultMap = true)
public class QuantForecastCycle {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cycleId;

    private String symbol;

    private LocalDateTime forecastTime;

    private String overallDecision;

    private String riskStatus;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String snapshotJson;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String reportJson;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String debateJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
