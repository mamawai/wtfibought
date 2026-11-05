package com.mawai.wiibagent.trader.trade;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** set_watch 的一条入参，模型写的原样；校验在 TradeGuard.validateWatch，落库在 TraderWatchStore */
public record WatchSpec(
        @JsonPropertyDescription("TOUCH_ABOVE / TOUCH_BELOW / CLOSE_ABOVE / CLOSE_BELOW / AT_TIME") String kind,
        @JsonPropertyDescription("Price level for TOUCH_* and CLOSE_*") Double level,
        @JsonPropertyDescription("Candle period for CLOSE_*: 5m / 15m / 1h / 4h / 1d") String period,
        @JsonPropertyDescription("Moment for AT_TIME, Beijing time yyyy-MM-dd HH:mm") String at,
        @JsonPropertyDescription("One sentence to your next self: what it means when this fires") String note) {
}
