package com.mawai.wiibcommon.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class CategoryAveragesDTO {
    private BigDecimal stockProfit;
    private BigDecimal cryptoProfit;
    private BigDecimal futuresProfit;
    private BigDecimal optionProfit;
    private BigDecimal predictionProfit;
    private BigDecimal gameProfit;
}
