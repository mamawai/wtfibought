package com.mawai.wiibagent.trader.trade;

import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;

/** open_position 的一档入场：数量加限价，不带限价就是市价档 */
public record EntryRung(
        @ToolParam(description = "Quantity in coins") BigDecimal quantity,
        @ToolParam(description = "Resting limit price; omit for market entry", required = false) BigDecimal limitPrice) {

    /** 入场参照价：限价档按限价，市价档按现价 */
    public BigDecimal price(BigDecimal mark) {
        return limitPrice == null ? mark : limitPrice;
    }

    public String orderType() {
        return limitPrice == null ? "MARKET" : "LIMIT";
    }
}
