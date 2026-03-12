package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class FuturesIncreaseRequest {
    private Long positionId;
    private BigDecimal quantity;
    private String orderType; // MARKET/LIMIT
    private BigDecimal limitPrice;
}
