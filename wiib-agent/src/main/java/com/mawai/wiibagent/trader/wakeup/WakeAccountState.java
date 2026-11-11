package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.trade.StopOrders;
import com.mawai.wiibagent.trader.trade.TargetRung;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 开场白里的账户状态块：持仓（带计划与修订史）+ 挂单（带挂出时刻与已挂时长）。盯盘条件不在这儿，在承诺块里。
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
                                   List<AiTraderPlan> plans, long boundaryTime,
                                   Map<Long, CaseFileBuilder.MarketWindow> paths) {
        Map<String, AiTraderPlan> planByKey = new HashMap<>();
        plans.forEach(p -> planByKey.put(TraderPlanStore.key(p.getSymbol(), p.getSide()), p));
        ObjectNode out = MAPPER.createObjectNode();
        out.put("equity", equity.setScale(2, RoundingMode.HALF_UP));
        // 当前北京时间，带年份：set_watch 的 AT_TIME 按这个写法填
        out.put("nowBeijing", BeijingTime.format(boundaryTime));
        // 全部持仓的止损同时被打掉，合计亏权益的百分之几
        out.put("lossAtAllStopsPctOfEquity", pctOfEquity(
                positions.stream().map(WakeAccountState::lossAtStops).reduce(BigDecimal.ZERO, BigDecimal::add), equity));
        out.set("positions", positionsJson(prompts, lang, equity, positions, planByKey, boundaryTime, paths));
        // 挂单同样给足：开仓挂单占坑且带着计划（成交后计划全文随持仓回注，这里给轻量版）。
        // 挂出时刻与已挂时长必须在：限价单挂了多久只有代码知道，模型据此执行自己写的作废条件
        if (pendingOrders != null && !pendingOrders.isEmpty()) {
            out.set("pendingOrders", pendingOrdersJson(prompts, lang, pendingOrders, planByKey, boundaryTime));
        }
        return MAPPER.writeValueAsString(out);
    }

    /**
     * 这个仓位当前的止损单全被打掉，相对开仓价亏多少（止损已经挪过成本的是负数，即锁住的利润）；不计手续费，没挂止损的仓位算 0。
     * 数量按仓位现量封顶：分批止盈之后止损单上的数量还是旧的
     */
    private static BigDecimal lossAtStops(FuturesPositionDTO p) {
        BigDecimal loss = BigDecimal.ZERO;
        for (FuturesStopLoss sl : StopOrders.effective(p)) {
            BigDecimal perUnit = "LONG".equals(p.getSide())
                    ? p.getEntryPrice().subtract(sl.getPrice()) : sl.getPrice().subtract(p.getEntryPrice());
            loss = loss.add(perUnit.multiply(sl.getQuantity()));
        }
        return loss;
    }

    private static BigDecimal pctOfEquity(BigDecimal amount, BigDecimal equity) {
        return amount.multiply(BigDecimal.valueOf(100)).divide(equity, 2, RoundingMode.HALF_UP);
    }

    /**
     * 持仓行：仓位事实 + 当前止损止盈（止盈带每档数量）
     * + 风险事实（浮盈是几 R、离止损多远、止损打掉亏权益百分之几）
     * + 自上次完成决策以来的波动（paths 按 positionId，没有就不带）+ 所属计划（含修订历史）。
     */
    private static ArrayNode positionsJson(PromptCatalog prompts, AgentLang lang, BigDecimal equity,
                                           List<FuturesPositionDTO> positions,
                                           Map<String, AiTraderPlan> planByKey,
                                           long boundaryTime, Map<Long, CaseFileBuilder.MarketWindow> paths) {
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
                // 离最近那档止损还有百分之几（相对标记价）
                BigDecimal nearest = p.getStopLosses().stream().map(FuturesStopLoss::getPrice)
                        .min(Comparator.comparing(price -> price.subtract(p.getMarkPrice()).abs())).orElseThrow();
                row.put("stopDistancePct", nearest.subtract(p.getMarkPrice()).abs().multiply(BigDecimal.valueOf(100))
                        .divide(p.getMarkPrice(), 2, RoundingMode.HALF_UP));
                row.put("lossAtStopPctOfEquity", pctOfEquity(lossAtStops(p), equity));
            }
            if (p.getTakeProfits() != null && !p.getTakeProfits().isEmpty()) {
                ArrayNode tps = MAPPER.createArrayNode();
                p.getTakeProfits().forEach(tp -> tps.add(MAPPER.createObjectNode()
                        .put("price", tp.getPrice()).put("quantity", tp.getQuantity())));
                row.set("currentTakeProfit", tps);
            }
            CaseFileBuilder.MarketWindow w = paths.get(p.getId());
            if (w != null) {
                row.set("sinceLastWake", MAPPER.createObjectNode()
                        .put("from", TIME_FMT.format(Instant.ofEpochMilli(w.from())))
                        .put("to", TIME_FMT.format(Instant.ofEpochMilli(w.to())))
                        .put("open", w.open())
                        .put("high", w.high())
                        .put("highAt", TIME_FMT.format(Instant.ofEpochMilli(w.highAt())))
                        .put("low", w.low())
                        .put("lowAt", TIME_FMT.format(Instant.ofEpochMilli(w.lowAt())))
                        .put("close", w.close())
                        .put("changePct", w.changePct()));
            }
            AiTraderPlan plan = planByKey.get(TraderPlanStore.key(p.getSymbol(), p.getSide()));
            if (plan != null) {
                // 浮盈是几 R：R = 开仓价到计划原始止损的距离；补立的计划可能没有原始止损
                BigDecimal oneR = plan.getStopLossPrice() == null ? BigDecimal.ZERO
                        : p.getEntryPrice().subtract(plan.getStopLossPrice()).abs();
                if (oneR.signum() > 0) {
                    BigDecimal move = "LONG".equals(p.getSide())
                            ? p.getMarkPrice().subtract(p.getEntryPrice()) : p.getEntryPrice().subtract(p.getMarkPrice());
                    row.put("unrealizedR", move.divide(oneR, 2, RoundingMode.HALF_UP));
                }
                // 开仓时刻按 sim 仓位自己的，不按计划的
                long opened = TradePairing.msOf(p.getCreatedAt());
                ObjectNode planJson = MAPPER.createObjectNode()
                        .put("playType", plan.getPlayType())
                        .put("signalsUsed", plan.getSignalsUsed())
                        .put("invalidationCondition", plan.getInvalidationCondition())
                        .put("entryPrice", plan.getEntryPrice())
                        .put("originalStop", plan.getStopLossPrice())
                        .put("openedAt", TIME_FMT.format(Instant.ofEpochMilli(opened)))
                        .put("heldFor", humanizeHeld(prompts, lang, boundaryTime - opened));
                // 开仓时设的止盈分档；没设就没有这一项
                List<TargetRung> targets = TargetRung.of(plan);
                if (!targets.isEmpty()) {
                    planJson.set("targets", MAPPER.valueToTree(targets));
                }
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
                AiTraderPlan plan = planByKey.get(
                        TraderPlanStore.key(o.getSymbol(), o.getOrderSide().substring("OPEN_".length())));
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

    /** 持有 / 已挂时长：两小时内按分钟，两天内按小时，再长按天 */
    static String humanizeHeld(PromptCatalog prompts, AgentLang lang, long ms) {
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
