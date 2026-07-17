package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class AssetSnapshotDTO {

    private LocalDate date;

    private BigDecimal totalAssets;

    private BigDecimal profit;

    private BigDecimal profitPct;

    // 五分类盈亏：bStock / crypto(现货+合约) / 大宗商品 / 预测 / 游戏
    private BigDecimal bstockProfit;

    private BigDecimal cryptoProfit;

    private BigDecimal commodityProfit;

    private BigDecimal predictionProfit;

    private BigDecimal gameProfit;

    // 日收益（对比昨日快照的差值）
    private BigDecimal dailyProfit;
    private BigDecimal dailyProfitPct;
    private BigDecimal dailyBstockProfit;
    private BigDecimal dailyCryptoProfit;
    private BigDecimal dailyCommodityProfit;
    private BigDecimal dailyPredictionProfit;
    private BigDecimal dailyGameProfit;
}
