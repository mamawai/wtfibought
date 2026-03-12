package com.mawai.wiibcommon.dto;

import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class FuturesPositionDTO {
    private Long id;
    private Long userId;
    private String symbol;
    private String side;
    private Integer leverage;
    private BigDecimal quantity;
    private BigDecimal entryPrice;
    private BigDecimal margin;
    private BigDecimal fundingFeeTotal;
    private List<FuturesStopLoss> stopLosses;
    private List<FuturesTakeProfit> takeProfits;
    private String status;
    private BigDecimal closedPrice;
    private BigDecimal closedPnl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 计算字段
    private BigDecimal currentPrice;
    private BigDecimal markPrice;
    private BigDecimal positionValue;
    private BigDecimal unrealizedPnl;
    private BigDecimal unrealizedPnlPct;
    private BigDecimal effectiveMargin;
    private BigDecimal maintenanceMargin;
    private BigDecimal liquidationPrice;
    private BigDecimal fundingFeePerCycle;
}
