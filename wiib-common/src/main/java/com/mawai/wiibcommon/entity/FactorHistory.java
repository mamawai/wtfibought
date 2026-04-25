package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.mawai.wiibcommon.handler.JsonbStringTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName(value = "factor_history", autoResultMap = true)
public class FactorHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String symbol;

    private String factorName;

    private BigDecimal factorValue;

    /** 外部数据自身的观测时间；去重用这个，不用入库时间。 */
    private LocalDateTime observedAt;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String metadataJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
