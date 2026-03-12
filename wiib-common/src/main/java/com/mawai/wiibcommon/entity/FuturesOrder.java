package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.mawai.wiibcommon.handler.FuturesStopLossListTypeHandler;
import com.mawai.wiibcommon.handler.FuturesTakeProfitListTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "futures_order", autoResultMap = true)
public class FuturesOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long positionId; // 关联仓位ID

    private String symbol; // 交易对

    private String orderSide; // OPEN_LONG开多 OPEN_SHORT开空 CLOSE_LONG平多 CLOSE_SHORT平空 INCREASE_LONG加多 INCREASE_SHORT加空

    private String orderType; // MARKET市价 LIMIT限价

    private BigDecimal quantity; // 下单数量(币)

    private Integer leverage; // 杠杆倍数

    private BigDecimal limitPrice; // 限价单触发价

    private BigDecimal frozenAmount; // 限价单冻结金额(保证金+手续费)

    private BigDecimal filledPrice; // 成交价

    private BigDecimal filledAmount; // 成交额(filledPrice*quantity)

    private BigDecimal marginAmount; // 实际使用保证金

    private BigDecimal commission; // 手续费

    private BigDecimal realizedPnl; // 已实现盈亏(平仓单才有)

    @TableField(typeHandler = FuturesStopLossListTypeHandler.class)
    private List<FuturesStopLoss> stopLosses; // 止损

    @TableField(typeHandler = FuturesTakeProfitListTypeHandler.class)
    private List<FuturesTakeProfit> takeProfits; // 止盈

    private String status; // PENDING待触发 TRIGGERED已触发 FILLED已成交 CANCELLED已取消 EXPIRED已过期 LIQUIDATED强平

    private LocalDateTime expireAt; // 限价单过期时间

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
