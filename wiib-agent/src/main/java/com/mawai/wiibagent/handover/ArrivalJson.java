package com.mawai.wiibagent.handover;

import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.trader.wakeup.AlertTrigger;
import com.mawai.wiibagent.trader.wakeup.Arrival;
import com.mawai.wiibagent.trader.wakeup.EquityDropTrigger;
import com.mawai.wiibagent.trader.wakeup.EventTrigger;
import com.mawai.wiibagent.trader.wakeup.WakeTrigger;
import com.mawai.wiibagent.trader.wakeup.WatchTrigger;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 到来的事过线时的 JSON。例行是 {"routine": 边界}；触发是 {"trigger": {...}}，带类型标签 type：
 * alert / event / equityDrop 是那个 record 本身的字段；盯盘命中只传条件 id、价、时刻，对面按 id 读库
 */
@Component
@RequiredArgsConstructor
public class ArrivalJson {

    private final TraderWatchStore watchStore;

    public ObjectNode encode(Arrival arrival) {
        ObjectNode out = MAPPER.createObjectNode();
        switch (arrival) {
            case Arrival.Routine r -> out.put("routine", r.boundary());
            case Arrival.Trigger t -> out.set("trigger", encode(t.trigger()));
        }
        return out;
    }

    private static ObjectNode encode(WakeTrigger trigger) {
        return switch (trigger) {
            case AlertTrigger a -> tagged("alert", a);
            case EventTrigger e -> tagged("event", e);
            case EquityDropTrigger d -> tagged("equityDrop", d);
            case WatchTrigger w -> MAPPER.createObjectNode().put("type", "watch")
                    .put("watchId", w.watch().getId()).put("price", w.price()).put("firedAt", w.firedAt());
        };
    }

    private static ObjectNode tagged(String type, WakeTrigger trigger) {
        ObjectNode node = MAPPER.valueToTree(trigger);
        node.put("type", type);
        return node;
    }

    /** 读回来；盯盘条件在库里找不到就抛 */
    public Arrival decode(JsonNode node) {
        if (node.has("routine")) {
            return new Arrival.Routine(node.get("routine").asLong());
        }
        JsonNode t = node.get("trigger");
        WakeTrigger trigger = switch (t.path("type").asString("")) {
            case "alert" -> MAPPER.treeToValue(t, AlertTrigger.class);
            case "event" -> MAPPER.treeToValue(t, EventTrigger.class);
            case "equityDrop" -> MAPPER.treeToValue(t, EquityDropTrigger.class);
            case "watch" -> {
                AiTraderWatch w = watchStore.byId(t.path("watchId").asLong());
                if (w == null) {
                    throw new IllegalArgumentException("盯盘条件不存在：" + t.path("watchId").asLong());
                }
                yield new WatchTrigger(w, t.path("price").asDecimal(), t.path("firedAt").asLong());
            }
            default -> throw new IllegalArgumentException("不认识的触发类型：" + t.path("type"));
        };
        return new Arrival.Trigger(trigger);
    }
}
