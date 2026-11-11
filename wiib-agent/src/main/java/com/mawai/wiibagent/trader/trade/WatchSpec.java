package com.mawai.wiibagent.trader.trade;

import org.springframework.ai.tool.annotation.ToolParam;

/**
 * set_watch 的一条入参，模型写的原样；校验在 TradeGuard.validateWatch，落库在 TraderWatchStore。带 action 的是预案。
 * 必填的只有 kind 和 note，其余用得到才填
 */
public record WatchSpec(
        @ToolParam(description = "TOUCH_ABOVE / TOUCH_BELOW / CLOSE_ABOVE / CLOSE_BELOW / AT_TIME") String kind,
        @ToolParam(required = false, description = "Price level for TOUCH_* and CLOSE_*") Double level,
        @ToolParam(required = false, description = "Candle period for CLOSE_*: 5m / 15m / 1h / 4h / 1d") String period,
        @ToolParam(required = false, description = "Moment for AT_TIME, Beijing time yyyy-MM-dd HH:mm") String at,
        @ToolParam(description = "One sentence to your next self: what it means when this fires") String note,
        @ToolParam(required = false, description = "An action the platform carries out by itself the moment this fires. Leave it out when the condition should only wake you") WatchAction action) {
}
