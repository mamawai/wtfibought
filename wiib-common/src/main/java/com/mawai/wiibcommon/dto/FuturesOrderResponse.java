package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class FuturesOrderResponse {
    private Long orderId;
    private Long userId;
    private Long positionId;
    private String symbol;
    private String orderSide;
    private String orderType;
    private String marginMode;
    private BigDecimal quantity;
    private Integer leverage;
    private BigDecimal limitPrice;
    private BigDecimal frozenAmount;
    private BigDecimal filledPrice;
    private BigDecimal filledAmount;
    private BigDecimal marginAmount;
    private BigDecimal commission;
    private BigDecimal liquidationFee; // 强平清算费，强平单才有
    private BigDecimal realizedPnl;
    private String status;
    private LocalDateTime createdAt;
    private Boolean isAiTrader;
}
