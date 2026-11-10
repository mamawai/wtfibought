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
    private String marginMode; // ISOLATED/CROSS
    private Integer leverage;
    private BigDecimal quantity;
    private BigDecimal openedQuantity; // 已平仓位才有：开仓单与加仓单成交数量合计，订单流水聚合（quantity 被分批平仓改写过）
    private BigDecimal entryPrice;
    private BigDecimal margin;
    private BigDecimal fundingFeeTotal;
    private List<FuturesStopLoss> stopLosses;
    private List<FuturesTakeProfit> takeProfits;
    private String memo;
    private String status;
    private BigDecimal closedPrice;
    private BigDecimal closedPnl; // 已平仓位的净盈亏：各平仓单盈亏−手续费−资金费，订单流水聚合
    private String closedBy; // 了结那张平仓单的状态：STOP_LOSS 止损 / TAKE_PROFIT 止盈 / LIQUIDATED 强平 / FILLED 主动平仓；没有平仓单为 null
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 计算字段
    private BigDecimal realizedPnl; // 持仓期间已实现盈亏：开/加仓手续费+已平部分净盈亏（不含资金费），订单流水聚合
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
