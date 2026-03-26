package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("user_asset_snapshot")
public class UserAssetSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate snapshotDate;

    private BigDecimal totalAssets;

    private BigDecimal profit;

    private BigDecimal profitPct;

    private BigDecimal stockProfit;

    private BigDecimal cryptoProfit;

    private BigDecimal futuresProfit;

    private BigDecimal optionProfit;

    private BigDecimal predictionProfit;

    private BigDecimal gameProfit;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
