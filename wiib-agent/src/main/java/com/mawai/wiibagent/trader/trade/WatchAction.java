package com.mawai.wiibagent.trader.trade;

import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;

/**
 * 预案的动作：挂在 set_watch 的一条条件上，命中时由代码当场执行。只有三种，都只会减风险。
 * 模型写的原样；形状校验在 TradeGuard.validateWatch，对着账户的校验在 TradeTools，执行在 PresetExecutor。
 * 必填的只有 type，其余按动作类型填
 */
public record WatchAction(
        @ToolParam(description = "MOVE_STOP / CLOSE / CANCEL_ORDER") String type,
        @ToolParam(required = false, description = "Position id, for MOVE_STOP and CLOSE") Long positionId,
        @ToolParam(required = false, description = "New stop-loss price, for MOVE_STOP") BigDecimal stopLossPrice,
        @ToolParam(required = false, description = "Percent of the position to close, for CLOSE; 100 closes all of it") BigDecimal closePercent,
        @ToolParam(required = false, description = "Pending order id, for CANCEL_ORDER") Long orderId) {
}
