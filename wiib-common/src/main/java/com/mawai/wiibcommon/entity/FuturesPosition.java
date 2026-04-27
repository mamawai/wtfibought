package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mawai.wiibcommon.handler.FuturesStopLossListTypeHandler;
import com.mawai.wiibcommon.handler.FuturesTakeProfitListTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "futures_position", autoResultMap = true)
public class FuturesPosition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String symbol; // 交易对 如BTCUSDT

    private String side; // LONG做多 SHORT做空

    private Integer leverage; // 杠杆倍数

    private BigDecimal quantity; // 持仓数量(币)

    private BigDecimal entryPrice; // 开仓均价

    private BigDecimal margin; // 保证金

    private BigDecimal fundingFeeTotal; // 累计资金费

    @TableField(typeHandler = FuturesStopLossListTypeHandler.class)
    private List<FuturesStopLoss> stopLosses;

    @TableField(typeHandler = FuturesTakeProfitListTypeHandler.class)
    private List<FuturesTakeProfit> takeProfits;

    private String memo; // AI策略标签: LEGACY_TREND/MR/BREAKOUT

    private String status; // OPEN持仓 CLOSED已平仓 LIQUIDATED已强平

    private BigDecimal closedPrice; // 平仓价

    private BigDecimal closedPnl; // 已实现盈亏

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
