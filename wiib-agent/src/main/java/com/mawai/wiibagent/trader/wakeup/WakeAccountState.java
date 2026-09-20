package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 开场白里的账户状态块：持仓（带计划与修订史）+ 挂单（带挂出时刻与已挂时长）。
 * 全静态无状态，prompts/lang 随参数进来。
 */
final class WakeAccountState {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private WakeAccountState() {
    }

    /**
     * 账户状态一次给足（持仓+计划+挂单）：模型不必再花工具预算查户口，
     * 预算留给行情求证。持仓携带交易计划与当前止损止盈，模型一眼看全。
     */
    static String accountStateJson(PromptCatalog prompts, AgentLang lang,
                                   BigDecimal equity, List<FuturesPositionDTO> positions,
                                   List<FuturesOrderResponse> pendingOrders,
                                   List<AiTraderPlan> plans, long boundaryTime) {
        Map<String, AiTraderPlan> planByKey = new HashMap<>();
        plans.forEach(p -> planByKey.put(TraderPlanStore.key(p.getSymbol(), p.getSide()), p));
        ObjectNode out = MAPPER.createObjectNode();
        out.put("equity", equity.setScale(2, RoundingMode.HALF_UP));
        out.set("positions", positionsJson(prompts, lang, positions, planByKey, boundaryTime));
        // 挂单同样给足：开仓挂单占坑且带着计划（成交后计划全文随持仓回注，这里给轻量版）。
        // 挂出时刻与已挂时长必须在：限价单挂了多久只有代码知道，模型据此执行自己写的作废条件
        if (pendingOrders != null && !pendingOrders.isEmpty()) {
            out.set("pendingOrders", pendingOrdersJson(prompts, lang, pendingOrders, planByKey, boundaryTime));
        }
        return MAPPER.writeValueAsString(out);
    }

    /** 持仓行：仓位事实 + 当前止损止盈 + 所属计划（含修订历史）。 */
    private static ArrayNode positionsJson(PromptCatalog prompts, AgentLang lang,
                                           List<FuturesPositionDTO> positions,
                                           Map<String, AiTraderPlan> planByKey, long boundaryTime) {
        ArrayNode ps = MAPPER.createArrayNode();
        for (FuturesPositionDTO p : positions) {
            ObjectNode row = MAPPER.createObjectNode()
                    .put("positionId", p.getId())
                    .put("symbol", p.getSymbol())
                    .put("side", p.getSide())
                    .put("quantity", p.getQuantity())
                    .put("leverage", p.getLeverage())
                    .put("entryPrice", p.getEntryPrice())
                    .put("markPrice", p.getMarkPrice())
                    .put("liquidationPrice", p.getLiquidationPrice())
                    .put("unrealizedPnl", p.getUnrealizedPnl());
            if (p.getStopLosses() != null && !p.getStopLosses().isEmpty()) {
                row.set("currentStopLoss", MAPPER.valueToTree(p.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList()));
            }
            if (p.getTakeProfits() != null && !p.getTakeProfits().isEmpty()) {
                row.set("currentTakeProfit", MAPPER.valueToTree(p.getTakeProfits().stream().map(FuturesTakeProfit::getPrice).toList()));
            }
            AiTraderPlan plan = planByKey.get(TraderPlanStore.key(p.getSymbol(), p.getSide()));
            if (plan != null) {
                // 开仓时刻按 sim 仓位自己的，不按计划的
                long opened = TradePairing.msOf(p.getCreatedAt());
                ObjectNode planJson = MAPPER.createObjectNode()
                        .put("playType", plan.getPlayType())
                        .put("signalsUsed", plan.getSignalsUsed())
                        .put("invalidationCondition", plan.getInvalidationCondition())
                        .put("entryPrice", plan.getEntryPrice())
                        .put("originalStop", plan.getStopLossPrice())
                        .put("target", plan.getTakeProfitPrice())
                        .put("openedAt", TIME_FMT.format(Instant.ofEpochMilli(opened)))
                        .put("heldFor", humanizeHeld(prompts, lang, boundaryTime - opened));
                // 修订历史也回注：无记忆的模型必须看到"上轮为什么动了止损/目标"
                if (plan.getRevisionsJson() != null && !plan.getRevisionsJson().isBlank()) {
                    ArrayNode revisions = MAPPER.readValue(plan.getRevisionsJson(), ArrayNode.class);
                    for (JsonNode r : revisions) {
                        // 库里存 epoch 毫秒，给模型看要时刻
                        ((ObjectNode) r).put("time", TIME_FMT.format(Instant.ofEpochMilli(r.path("time").asLong(0))));
                    }
                    planJson.set("revisions", revisions);
                }
                row.set("plan", planJson);
            }
            ps.add(row);
        }
        return ps;
    }

    /** 挂单行：订单事实 + 开仓挂单所属计划的轻量版。 */
    private static ArrayNode pendingOrdersJson(PromptCatalog prompts, AgentLang lang,
                                               List<FuturesOrderResponse> pendingOrders,
                                               Map<String, AiTraderPlan> planByKey, long boundaryTime) {
        ArrayNode po = MAPPER.createArrayNode();
        for (FuturesOrderResponse o : pendingOrders) {
            ObjectNode row = MAPPER.createObjectNode()
                    .put("orderId", o.getOrderId())
                    .put("symbol", o.getSymbol())
                    .put("orderSide", o.getOrderSide())
                    .put("quantity", o.getQuantity())
                    .put("limitPrice", o.getLimitPrice())
                    .put("leverage", o.getLeverage());
            if (o.getOrderSide() != null && o.getOrderSide().startsWith("OPEN_")) {
                AiTraderPlan plan = planByKey.get(TraderPlanStore.key(o.getSymbol(),
                        o.getOrderSide().substring("OPEN_".length())));
                if (plan != null) {
                    // 挂出时刻按订单自己的
                    long placed = TradePairing.msOf(o.getCreatedAt());
                    row.set("plan", MAPPER.createObjectNode()
                            .put("playType", plan.getPlayType())
                            .put("invalidationCondition", plan.getInvalidationCondition())
                            .put("placedAt", TIME_FMT.format(Instant.ofEpochMilli(placed)))
                            .put("pendingFor", humanizeHeld(prompts, lang, boundaryTime - placed)));
                }
            }
            po.add(row);
        }
        return po;
    }

    private static String humanizeHeld(PromptCatalog prompts, AgentLang lang, long ms) {
        long min = Math.max(0, ms / 60_000);
        if (min < 120) {
            return prompts.get(lang, "trader.wake.held.minutes", Map.of("n", min));
        }
        long hours = min / 60;
        return hours < 48
                ? prompts.get(lang, "trader.wake.held.hours", Map.of("n", hours))
                : prompts.get(lang, "trader.wake.held.days", Map.of("n", hours / 24));
    }
}
