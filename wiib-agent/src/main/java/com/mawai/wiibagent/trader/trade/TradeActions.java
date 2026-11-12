package com.mawai.wiibagent.trader.trade;

import tools.jackson.databind.JsonNode;

import java.util.List;

/** 反手记为一次调用，交易记录和复盘分别读取平仓、反向开仓的结果。 */
public final class TradeActions {
    private TradeActions() {
    }

    public static List<JsonNode> legs(JsonNode action) {
        if ("reverse_position".equals(action.path("tool").asString(null)) && action.has("result")) {
            return List.of(action.get("result").get("close"), action.get("result").get("open"));
        }
        return List.of(action);
    }

    public static boolean opened(JsonNode action) {
        return legs(action).stream().anyMatch(a -> "open_position".equals(a.path("tool").asString(null))
                && "ok".equals(a.path("status").asString(null)));
    }
}
