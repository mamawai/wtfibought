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

    private BigDecimal stockProfit;

    private BigDecimal cryptoProfit;

    private BigDecimal futuresProfit;

    private BigDecimal optionProfit;

    private BigDecimal predictionProfit;

    private BigDecimal gameProfit;

    // 日收益（对比昨日快照的差值）
    private BigDecimal dailyProfit;
    private BigDecimal dailyProfitPct;
    private BigDecimal dailyStockProfit;
    private BigDecimal dailyCryptoProfit;
    private BigDecimal dailyFuturesProfit;
    private BigDecimal dailyOptionProfit;
    private BigDecimal dailyPredictionProfit;
    private BigDecimal dailyGameProfit;
}
