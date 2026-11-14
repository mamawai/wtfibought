package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.entity.AiTraderPlan;
import lombok.Data;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 计划里的一档入场：最初的安排，加上后来成交或撤单的事实。存在 ai_trader_plan.entries_json 里。
 * limitPrice / quantity / stop / targets / placedAt / add 是下单时写下的，之后不改
 */
@Data
public class PlanEntry {
    /** sim 订单 id；下单结果未知、还没认领到时为空 */
    private Long orderId;
    /** 限价；市价档为空 */
    private BigDecimal limitPrice;
    private BigDecimal quantity;
    /** 下单时带的止损价 */
    private BigDecimal stop;
    /** 下单时带的止盈分档，没设为空 */
    private List<TargetRung> targets;
    /** 挂出时刻 ms */
    private long placedAt;
    /** 是不是之后加仓带来的档；首批为 false */
    private boolean add;
    /** PENDING 挂着 / FILLED 成交 / CANCELLED 已撤 / UNKNOWN 下单结果未知 */
    private String status;
    private BigDecimal filledPrice;
    /** 实际开仓手续费 */
    private BigDecimal fee;
    /** 成交或撤单的时刻 ms */
    private Long closedAt;
    /** 撤单理由 */
    private String reason;

    /** 计划里的各档；没有逐档记录给空表 */
    public static List<PlanEntry> read(AiTraderPlan plan) {
        return plan == null || plan.getEntriesJson() == null ? new ArrayList<>()
                : new ArrayList<>(List.of(MAPPER.readValue(plan.getEntriesJson(), PlanEntry[].class)));
    }

    public static void save(AiTraderPlan plan, List<PlanEntry> entries) {
        plan.setEntriesJson(MAPPER.writeValueAsString(entries));
    }

    /** 给模型看的轻量版：每档的限价、成交价、数量、状态 */
    public static ArrayNode summary(AiTraderPlan plan) {
        ArrayNode out = MAPPER.createArrayNode();
        for (PlanEntry e : read(plan)) {
            out.add(MAPPER.createObjectNode()
                    .put("limitPrice", e.limitPrice)
                    .put("filledPrice", e.filledPrice)
                    .put("quantity", e.quantity)
                    .put("status", e.status));
        }
        return out;
    }
}
