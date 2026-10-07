package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CryptoOrderResponse {

    private Long orderId;
    private String symbol;
    private String orderSide;
    private String orderType;
    private BigDecimal quantity;
    private Integer leverage;
    private BigDecimal limitPrice;
    private BigDecimal filledPrice;
    private BigDecimal filledAmount;
    private BigDecimal commission;
    private BigDecimal triggerPrice;
    private LocalDateTime triggeredAt;
    private String status;
    private LocalDateTime createdAt;
}
