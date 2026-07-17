package com.mawai.wiibcommon.dto;

import lombok.Data;
import java.math.BigDecimal;

/** 五分类能力百分位：值 = 超过多少百分比的其他用户（0~100） */
@Data
public class CategoryAveragesDTO {
    private BigDecimal bstockProfit;
    private BigDecimal cryptoProfit;
    private BigDecimal commodityProfit;
    private BigDecimal predictionProfit;
    private BigDecimal gameProfit;
}
