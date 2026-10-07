package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CryptoOrderRequest {

    private String symbol;

    /** 交易数量（支持小数，如0.001） */
    private BigDecimal quantity;

    /** MARKET/LIMIT */
    private String orderType;

    private BigDecimal limitPrice;

    /** 杠杆倍数 1-10 */
    private Integer leverageMultiple;
}
