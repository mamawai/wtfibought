package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibquant.external.sim.SimOrderRetry;
import com.mawai.wiibquant.external.sim.SimTradeApi;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.mawai.wiibagent.trader.trade.StopOrders.pctOfEquity;
import static com.mawai.wiibagent.trader.trade.TradeActions.NOT_ATTEMPTED;
import static com.mawai.wiibagent.trader.trade.TradeActions.OK;
import static com.mawai.wiibagent.trader.trade.TradeActions.PARTIAL;
import static com.mawai.wiibagent.trader.trade.TradeActions.REJECTED;
import static com.mawai.wiibagent.trader.trade.TradeActions.UNKNOWN;
import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 开仓这一套：过护栏、逐档下单、立计划、出回执。普通开仓和反手的新仓都走这里。
 * 跟 {@link TradeTools} 一样每次唤醒 new 一个，由它创建；动作轨迹不归这里管，回执交回去由它记。
 */
@Slf4j
class PositionOpener {

    private final SimTradeClient simTradeClient;
    private final long simUserId;
    private final Set<String> symbolWhitelist;
    private final TraderPlanStore planStore;
    private final TradeTools.WakeCtx ctx;
    private final PromptCatalog prompts;
    private final MessageCatalog messages;
    /** 开仓那一刻的上下文快照，随计划落库 */
    private final EntryContext entryContext;

    PositionOpener(SimTradeClient simTradeClient, long simUserId, Set<String> symbolWhitelist,
                   TraderPlanStore planStore, TradeTools.WakeCtx ctx, PromptCatalog prompts,
                   MessageCatalog messages, EntryContext entryContext) {
        this.simTradeClient = simTradeClient;
        this.simUserId = simUserId;
        this.symbolWhitelist = symbolWhitelist;
        this.planStore = planStore;
        this.ctx = ctx;
        this.prompts = prompts;
        this.messages = messages;
        this.entryContext = entryContext;
    }

    /** 护栏检查：通过返回 null，否则是拒因。 */
    String validate(TradeGuard.OpenReq req, TradeTools.Account acct, BigDecimal equity, BigDecimal mark) {
        String reject = TradeGuard.validateOpen(req, equity, mark, symbolWhitelist, ctx.risk(), acct.snaps(),
                prompts, ctx.lang());
        if (reject != null) {
            return reject;
        }
        FuturesPositionDTO sameSide = acct.sameSide(req.symbol(), req.side());
        // 止盈价位数按 sim 并单后的算：仓位上还摊得到数量的旧价位、同方向挂单带的价位，加本次的新价位，同价算一档
        Set<BigDecimal> prices = new TreeSet<>();
        if (sameSide != null && sameSide.getTakeProfits() != null) {
            Comparator<FuturesTakeProfit> byPrice = Comparator.comparing(FuturesTakeProfit::getPrice);
            BigDecimal left = sameSide.getQuantity();
            for (FuturesTakeProfit tp : sameSide.getTakeProfits().stream()
                    .sorted("LONG".equals(req.side()) ? byPrice : byPrice.reversed()).toList()) {
                if (left.signum() <= 0) {
                    break;
                }
                prices.add(tp.getPrice());
                left = left.subtract(tp.getQuantity());
            }
        }
        List<FuturesOrderResponse> pendingOpens = acct.pendingOpens(req.symbol(), req.side());
        for (FuturesOrderResponse order : pendingOpens) {
            if (order.getTakeProfits() != null) {
                order.getTakeProfits().forEach(tp -> prices.add(tp.getPrice()));
            }
        }
        int hanging = prices.size();
        req.takeProfits().forEach(tp -> prices.add(tp.price()));
        if (prices.size() > TradeGuard.SIM_SPLIT_LIMIT) {
            return prompts.get(ctx.lang(), "trader.reject.targetRungsOnAdd",
                    Map.of("hanging", hanging, "limit", TradeGuard.SIM_SPLIT_LIMIT));
        }
        // 杠杆分档预检的合计名义额：同向持仓按标记价，同向挂单按限价，本次各档按入场参照价
        BigDecimal total = req.notional(mark);
        if (sameSide != null) {
            total = total.add(sameSide.getQuantity().multiply(mark));
        }
        for (FuturesOrderResponse o : pendingOpens) {
            total = total.add(o.getQuantity().multiply(o.getLimitPrice()));
        }
        List<SimTradeApi.Bracket> brackets = simTradeClient.getBrackets(List.of(req.symbol())).get(req.symbol());
        return TradeGuard.validateBracket(total, req.leverage(), brackets, prompts, ctx.lang());
    }

    /**
     * 逐档下单、立计划、出回执。先挂限价档，最后下市价档。
     * 回执的 status：ok 每档都成了；partial 有档被拒、已成交的留着；unknown 有档结果未知；rejected 被拒且什么都没留下。
     */
    ObjectNode execute(TradeGuard.OpenReq req, TradeTools.Account acct, BigDecimal equity, BigDecimal mark) {
        FuturesPositionDTO sameSide = acct.sameSide(req.symbol(), req.side());
        boolean coversPlan = sameSide != null || !acct.pendingOpens(req.symbol(), req.side()).isEmpty();
        BigDecimal entryRef = req.entryPrice(mark);
        AiTraderPlan live = coversPlan ? planStore.liveFor(ctx.traderId(), ctx.roundNo(), req.symbol(), req.side()) : null;
        // 加仓沿用这份计划的订单组，首开新起一组
        String groupId = live != null && live.getOrderGroup() != null ? live.getOrderGroup() : UUID.randomUUID().toString();
        // 下单前的费用与风险估算。
        ObjectNode batch = OpenFacts.of(req, mark, equity);
        batch.set("entries", MAPPER.valueToTree(req.entries()));
        ArrayNode results = MAPPER.createArrayNode();
        List<PlanEntry> planned = new ArrayList<>();
        String status = OK;
        String error = null;
        // 市价档响应带仓位 id；限价加仓先认它将并入的那个仓；限价首开为 null，成交后才有
        Long positionId = sameSide == null ? null : sameSide.getId();
        List<EntryRung> limitsFirst = req.entries().stream()
                .sorted(Comparator.comparing(e -> e.limitPrice() == null)).toList();
        for (EntryRung entry : limitsFirst) {
            ObjectNode result = MAPPER.createObjectNode()
                    .put("quantity", entry.quantity())
                    .put("limitPrice", entry.limitPrice());
            results.add(result);
            if (!OK.equals(status)) {
                result.put("status", NOT_ATTEMPTED);
                continue;
            }
            PlanEntry saved = new PlanEntry();
            saved.setLimitPrice(entry.limitPrice());
            saved.setQuantity(entry.quantity());
            saved.setStop(req.stopLossPrice());
            saved.setTargets(req.takeProfits());
            saved.setPlacedAt(System.currentTimeMillis());
            saved.setAdd(coversPlan);
            FuturesOpenRequest openReq = entryRequest(req, entry, groupId);
            FuturesOrderResponse resp;
            try {
                resp = SimOrderRetry.send(() -> simTradeClient.openPosition(simUserId, openReq));
            } catch (SimOrderRetry.UnknownOutcome e) {
                // 结果未知：不撤别的档，记成未知留给对账认领
                status = UNKNOWN;
                error = prompts.get(ctx.lang(), "trader.reject.orderUnknown");
                saved.setStatus("UNKNOWN");
                planned.add(saved);
                result.put("status", UNKNOWN);
                continue;
            } catch (Exception e) {
                String reason = TradeTools.simReason(e, messages, ctx.lang());
                result.put("status", REJECTED).put("reason", reason);
                int withdrawn = withdraw(planned, results, reason);
                if (planned.stream().anyMatch(p -> !"CANCELLED".equals(p.getStatus()))) {
                    status = PARTIAL;
                    error = reason;
                } else {
                    status = REJECTED;
                    error = withdrawn == 0 ? reason : prompts.get(ctx.lang(), "trader.reject.entriesWithdrawn",
                            Map.of("reason", reason, "n", withdrawn));
                }
                continue;
            }
            result.setAll((ObjectNode) MAPPER.valueToTree(resp));
            result.put("status", resp.getStatus().toLowerCase(Locale.ROOT));
            saved.setOrderId(resp.getOrderId());
            saved.setStatus(resp.getStatus());
            if ("FILLED".equals(resp.getStatus())) {
                saved.setFilledPrice(resp.getFilledPrice());
                saved.setFee(resp.getCommission());
                saved.setClosedAt(TradePairing.msOf(resp.getUpdatedAt()));
            }
            if (resp.getPositionId() != null) {
                positionId = resp.getPositionId();
            }
            planned.add(saved);
        }
        if (!REJECTED.equals(status)) {
            persistPlan(req, entryRef, coversPlan, positionId, groupId, planned);
        }
        OpenFacts.addAtr(batch, req, entryRef, ctx.interval(),
                entryContext.atr14(req.symbol(), ctx.interval(), System.currentTimeMillis()));
        ObjectNode facts = MAPPER.createObjectNode();
        facts.set("batch", batch);
        facts.set("sameSide", sameSideFacts(req, acct, planned, equity, mark));
        ObjectNode receipt = MAPPER.createObjectNode().put("status", status);
        receipt.set("entries", results);
        receipt.set("facts", facts);
        if (error != null) {
            receipt.put("reason", error);
        }
        return receipt;
    }

    /** 有档被拒：把本次已挂上的档撤回来，撤不掉的（已经成交）留着。返回撤掉了几档 */
    private int withdraw(List<PlanEntry> planned, ArrayNode results, String reason) {
        int withdrawn = 0;
        for (PlanEntry entry : planned) {
            if (!"PENDING".equals(entry.getStatus())) {
                continue;
            }
            try {
                FuturesOrderResponse cancelled = simTradeClient.cancelOrder(simUserId, entry.getOrderId());
                entry.setStatus("CANCELLED");
                entry.setReason(reason);
                entry.setClosedAt(TradePairing.msOf(cancelled.getUpdatedAt()));
                for (JsonNode row : results) {
                    if (row.path("orderId").asLong(-1) == entry.getOrderId()) {
                        ((ObjectNode) row).put("status", "cancelled");
                    }
                }
                withdrawn++;
            } catch (Exception e) {
                log.info("[PositionOpener] 分档撤回未成功 orderId={} msg={}", entry.getOrderId(), e.getMessage());
            }
        }
        return withdrawn;
    }

    /** 这个币这个方向合计：下单前已有的仓位和挂单按现挂止损，加上本次留下的各档，全部被打掉的价差亏损 */
    private ObjectNode sameSideFacts(TradeGuard.OpenReq req, TradeTools.Account acct, List<PlanEntry> planned,
                                     BigDecimal equity, BigDecimal mark) {
        BigDecimal loss = BigDecimal.ZERO;
        for (FuturesPositionDTO p : acct.positions()) {
            if (req.symbol().equals(p.getSymbol()) && req.side().equals(p.getSide())) {
                loss = loss.add(StopOrders.lossAtStops(p));
            }
        }
        for (FuturesOrderResponse o : acct.pendingOpens(req.symbol(), req.side())) {
            loss = loss.add(StopOrders.lossAtStops(o));
        }
        boolean isLong = "LONG".equals(req.side());
        for (PlanEntry entry : planned) {
            if ("CANCELLED".equals(entry.getStatus())) {
                continue;
            }
            BigDecimal price = entry.getFilledPrice() != null ? entry.getFilledPrice()
                    : entry.getLimitPrice() == null ? mark : entry.getLimitPrice();
            BigDecimal perUnit = isLong ? price.subtract(entry.getStop()) : entry.getStop().subtract(price);
            loss = loss.add(perUnit.multiply(entry.getQuantity()));
        }
        return MAPPER.createObjectNode()
                .put("lossAtStops", loss)
                .put("riskPctOfEquity", pctOfEquity(loss, equity));
    }

    private FuturesOpenRequest entryRequest(TradeGuard.OpenReq req, EntryRung entry, String groupId) {
        FuturesOpenRequest openReq = new FuturesOpenRequest();
        openReq.setSymbol(req.symbol());
        openReq.setSide(req.side());
        // trader 使用全仓模式。
        openReq.setMarginMode(FuturesPosition.CROSS);
        openReq.setOrderType(entry.orderType());
        openReq.setQuantity(entry.quantity());
        openReq.setLeverage(req.leverage());
        openReq.setLimitPrice(entry.limitPrice());
        openReq.setGroupId(groupId);
        openReq.setMemo("ai_trader:" + req.playType());
        // 本次订单的幂等键。
        openReq.setClientRequestId(UUID.randomUUID().toString());
        FuturesOpenRequest.StopLoss sl = new FuturesOpenRequest.StopLoss();
        sl.setPrice(req.stopLossPrice());
        sl.setQuantity(entry.quantity());
        openReq.setStopLosses(List.of(sl));
        List<BigDecimal> rungQty = TargetRung.quantities(req.takeProfits(), entry.quantity(), req.symbol());
        List<FuturesOpenRequest.TakeProfit> tps = new ArrayList<>();
        for (int i = 0; i < rungQty.size(); i++) {
            FuturesOpenRequest.TakeProfit tp = new FuturesOpenRequest.TakeProfit();
            tp.setPrice(req.takeProfits().get(i).price());
            tp.setQuantity(rungQty.get(i));
            tps.add(tp);
        }
        openReq.setTakeProfits(tps);
        return openReq;
    }

    /**
     * 成交/挂单即落计划（下轮唤醒回注）：同键已有持仓或挂单走覆盖，否则新立。
     * 写失败只记日志不回错——交易已真实发生，回错误会诱导模型重复开仓。
     */
    private void persistPlan(TradeGuard.OpenReq req, BigDecimal entryRef, boolean coversPlan, Long positionId,
                             String groupId, List<PlanEntry> entries) {
        try {
            AiTraderPlan plan = new AiTraderPlan();
            plan.setTraderId(ctx.traderId());
            plan.setRoundNo(ctx.roundNo());
            plan.setSymbol(req.symbol());
            plan.setSide(req.side());
            plan.setPlayType(req.playType());
            plan.setSignalsUsed(req.signalsUsed());
            plan.setInvalidationCondition(req.invalidationCondition());
            plan.setEntryPrice(entryRef);
            plan.setStopLossPrice(req.stopLossPrice());
            plan.setTakeProfitsJson(TargetRung.toJson(req.takeProfits()));
            plan.setOpenedWakeTime(ctx.wakeTime());
            // 限价加仓先绑到它将并入的那个仓（不然下轮对账见键上有仓就把还挂着的单当成交了），首开留空等成交后对账补上
            plan.setPositionId(positionId);
            plan.setOrderGroup(groupId);
            PlanEntry.save(plan, entries);
            if (coversPlan) {
                planStore.cover(plan, ctx.lang());
            } else {
                planStore.open(plan, group -> simTradeClient.getGroupOrders(simUserId, group), ctx.lang());
            }
            // 入场上下文要出网取，异步抓完按列补写到这条计划（覆盖时 id 是旧条目的）
            long nowMs = System.currentTimeMillis();
            Thread.startVirtualThread(() -> captureEntryContext(plan.getId(), req, entryRef, nowMs));
        } catch (Exception e) {
            log.warn("[PositionOpener] 计划落库失败 traderId={} {} msg={}", ctx.traderId(), req.symbol(), e.getMessage());
        }
    }

    /** 抓开仓那一刻的上下文写进计划的 entry_ctx_json；抓不到只记 warn */
    private void captureEntryContext(Long planId, TradeGuard.OpenReq req, BigDecimal entryRef, long nowMs) {
        try {
            planStore.setEntryContext(planId, entryContext.capture(req.symbol(), req.orderType(), entryRef,
                    req.stopLossPrice(), req.leverage(), nowMs));
        } catch (Exception e) {
            log.warn("[PositionOpener] 入场上下文抓取失败 traderId={} {} msg={}", ctx.traderId(), req.symbol(), e.getMessage());
        }
    }
}
