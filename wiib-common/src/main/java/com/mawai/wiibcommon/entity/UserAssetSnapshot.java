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

    // 五分类盈亏口径：bStock(代币化美股现货) / crypto(币现货+币合约) / 大宗商品(金油,现货遗留+合约) / 预测 / 游戏
    private BigDecimal bstockProfit;

    private BigDecimal cryptoProfit;

    private BigDecimal commodityProfit;

    private BigDecimal predictionProfit;

    private BigDecimal gameProfit;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
