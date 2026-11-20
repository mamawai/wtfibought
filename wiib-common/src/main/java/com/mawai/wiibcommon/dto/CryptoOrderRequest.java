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

    /** 杠杆倍数，上限是配置 trading.margin.max-leverage（默认 50） */
    private Integer leverageMultiple;
}
