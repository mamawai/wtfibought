package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderProtectionRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibquant.external.sim.SimOrderRetry;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static com.mawai.wiibagent.trader.trade.StopOrders.pctOfEquity;
import static com.mawai.wiibagent.trader.trade.TradeActions.ERROR;
import static com.mawai.wiibagent.trader.trade.TradeActions.LIQUIDATED;
import static com.mawai.wiibagent.trader.trade.TradeActions.NOT_ATTEMPTED;
import static com.mawai.wiibagent.trader.trade.TradeActions.OK;
import static com.mawai.wiibagent.trader.trade.TradeActions.PARTIAL;
import static com.mawai.wiibagent.trader.trade.TradeActions.REJECTED;
import static com.mawai.wiibagent.trader.trade.TradeActions.UNKNOWN;
import static com.mawai.wiibagent.trader.trade.TradeActions.replyPrefix;

/**
 * 交易工具（非 Spring bean）：每次唤醒 new 一个，绑定该 trader 的 sim 子账户与白名单。
 * 所有调用（含被 TradeGuard 拒绝的）都记入 actions 列表——决策日志的动作轨迹，
 * 拒绝原因原样返回给模型，模型可自行修正重试。
 */
@Slf4j
public class TradeTools {

    /**
     * 唤醒上下文：计划落库与风险护栏所需的 trader 侧信息；interval＝心跳周期，开仓回执里的 ATR 按它算；
     * wakeTime＝这一轮的唤醒时刻（例行轮是 K 线边界，手动和非例行轮是醒来那一刻），和决策行的 wake_time 是同一个值；
     * deadlineMs＝本轮预算耗尽的墙钟时刻。
     * lang＝这只 trader 主人的语言：拒因既回给模型也公开在竞技场时间线上，两处都得跟它走。
     */
    public record WakeCtx(long traderId, int roundNo, String interval, long wakeTime, long deadlineMs,
                          TraderRiskConfig risk, AgentLang lang) {
    }

    /** 改账户状态的工具名：同一批 tool_call 里有它们就整批串行，不和别的调用交错（ReactLoop.serialTools） */
    public static final Set<String> SERIAL_TOOLS = Set.of(
            "open_position", "close_position", "reverse_position", "set_stop_loss", "set_take_profit", "write_plan", "cancel_order", "set_watch");

    private final SimTradeClient simTradeClient;
    private final long simUserId;
    private final Set<String> symbolWhitelist;
    /** 能布盯盘条件的币：平台在采的全部币，不限可交易的那几个 */
    private final Set<String> watchSymbols;
    /** 现价查询（symbol → mark price）；由唤醒回路注入，取 Binance 实时标记价 */
    private final Function<String, BigDecimal> markPrice;
    private final TraderPlanStore planStore;
    /** 模型给自己布的盯盘条件 */
    private final TraderWatchStore watchStore;
    private final WakeCtx ctx;
    /** 拒因文案：回给模型、也进公开时间线，跟 {@code ctx.lang()} 走 */
    private final PromptCatalog prompts;
    /** sim 那侧的拒因按错误码在这儿成文（余额不足/止损价非法等），同样跟 {@code ctx.lang()} */
    private final MessageCatalog messages;
    /** 开仓那一套：护栏、逐档下单、立计划；普通开仓和反手的新仓共用 */
    private final PositionOpener opener;
    /** 本轮是否已进入可以开仓的阶段 */
    private final BooleanSupplier openingAllowed;
    /** 本次唤醒的动作轨迹，唤醒回路收走序列化进 ai_trader_decision.actions_json */
    private final List<ObjectNode> actions = new ArrayList<>();

    public TradeTools(SimTradeClient simTradeClient, long simUserId, Set<String> symbolWhitelist, Set<String> watchSymbols,
                      Function<String, BigDecimal> markPrice,
                      TraderPlanStore planStore, TraderWatchStore watchStore, WakeCtx ctx,
                      PromptCatalog prompts, MessageCatalog messages, EntryContext entryContext,
                      BooleanSupplier openingAllowed) {
        this.simTradeClient = simTradeClient;
        this.simUserId = simUserId;
        this.symbolWhitelist = symbolWhitelist;
        this.watchSymbols = watchSymbols;
        this.markPrice = markPrice;
        this.planStore = planStore;
        this.watchStore = watchStore;
        this.ctx = ctx;
        this.prompts = prompts;
        this.messages = messages;
        this.opener = new PositionOpener(simTradeClient, simUserId, symbolWhitelist, planStore, ctx, prompts,
                messages, entryContext);
        this.openingAllowed = openingAllowed;
    }

    /** 唤醒回路收轨迹：被打断的那批并发 get_account 可能还在往里记，拷一份出去 */
    public synchronized List<ObjectNode> actions() {
        return List.copyOf(actions);
    }

    // 本类工具的描述都在词表 tool.<工具名>（见 LocalizedToolCallbacks）
    @Tool(name = "get_account")
    public String getAccount() {
        try {
            return ok("get_account", null, accountSnapshot());
        } catch (Exception e) {
            return fail("get_account", null, e);
        }
    }

    /** 只读账户快照，不增加模型的工具调用记录 */
    public String accountSnapshot() {
        ObjectNode out = MAPPER.createObjectNode();
        Map<String, Object> balance = simTradeClient.getBalanceDetail(simUserId);
        List<FuturesPositionDTO> positions = simTradeClient.getAllPositions(simUserId);
        // 权益是 sim 算好的，和开场白同一个数；available 是 sim 全仓账本的下单额度，钱包余额里还躺着已占用的保证金，不能当可用
        BigDecimal equity = SimTradeClient.equityOf(balance);
        out.put("equity", equity);
        out.put("lossAtAllStopsPctOfEquity", pctOfEquity(positions.stream().map(StopOrders::lossAtStops)
                .reduce(BigDecimal.ZERO, BigDecimal::add), equity));
        out.put("available", decimal(balance.get("available")));
        out.put("walletBalance", decimal(balance.get("balance")));
        ArrayNode ps = MAPPER.createArrayNode();
        for (FuturesPositionDTO p : positions) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("positionId", p.getId());
            row.put("symbol", p.getSymbol());
            row.put("side", p.getSide());
            row.put("quantity", p.getQuantity());
            row.put("entryPrice", p.getEntryPrice());
            row.put("leverage", p.getLeverage());
            row.put("margin", p.getMargin());
            row.put("unrealizedPnl", p.getUnrealizedPnl());
            row.put("liquidationPrice", p.getLiquidationPrice());
            row.set("stopLosses", MAPPER.valueToTree(p.getStopLosses()));
            row.set("takeProfits", MAPPER.valueToTree(p.getTakeProfits()));
            row.put("lossAtStopPctOfEquity", pctOfEquity(StopOrders.lossAtStops(p), equity));
            ps.add(row);
        }
        out.set("positions", ps);
        List<FuturesOrderResponse> pending = simTradeClient.getPendingOrders(simUserId, null);
        ArrayNode orders = MAPPER.valueToTree(pending);
        for (int i = 0; i < pending.size(); i++) {
            FuturesOrderResponse order = pending.get(i);
            ObjectNode row = (ObjectNode) orders.get(i);
            row.put("lossAtStopPctOfEquity", pctOfEquity(StopOrders.lossAtStops(order), equity));
        }
        out.set("pendingOrders", orders);
        out.put("lossIfPendingFillPctOfEquity", pctOfEquity(pending.stream().map(StopOrders::lossAtStops)
                .reduce(BigDecimal.ZERO, BigDecimal::add), equity));
        return MAPPER.writeValueAsString(out);
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? null : new BigDecimal(String.valueOf(v));
    }

    @Tool(name = "open_position")
    public String openPosition(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                               @ToolParam(description = "LONG or SHORT") String side,
                               @ToolParam(description = "1–5 entry rungs {quantity, limitPrice}; omit limitPrice for market, at most one market rung") List<EntryRung> entries,
                               @ToolParam(description = "Leverage; must land inside the range your owner configured (see system prompt)") int leverage,
                               // 必须是包装类型：primitive 漏传会被绑成 0.0，护栏的 null 检查就成了摆设，
                               // LONG 的方向校验「0 >= 入场价」为假直接放行——裸多单就是这么开出去的
                               @ToolParam(description = "Stop-loss price, REQUIRED") Double stopLossPrice,
                               @ToolParam(description = "Take-profit rungs, optional: up to 3, each {price, percent}; they are recorded as the plan's targets", required = false) List<TargetRung> takeProfits,
                               @ToolParam(description = "Thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER") String playType,
                               @ToolParam(description = "One sentence citing concrete data behind this trade") String signalsUsed,
                               @ToolParam(description = "Market condition that proves this thesis wrong, e.g. '1h close back below 64200 box top'") String invalidationCondition) {
        TradeGuard.OpenReq req = new TradeGuard.OpenReq(symbol, side, entries, leverage,
                stopLossPrice == null ? null : BigDecimal.valueOf(stopLossPrice),
                takeProfits == null ? List.of() : takeProfits,
                playType, signalsUsed, invalidationCondition);
        ObjectNode argSummary = openArgs(req);
        if (roundExpired()) {
            return expired("open_position", argSummary);
        }
        if (!openingAllowed.getAsBoolean()) {
            return rejected("open_position", argSummary, prompts.get(ctx.lang(), "trader.reject.openingStage"));
        }
        // 白名单挡在行情查询之前：模型重试时会丢参数（真实发生过），空 symbol 打到上游
        // 会拉回全市场 premiumIndex 数组炸掉解析，模型收到的就不是可修正的拒因了
        if (symbol == null || !symbolWhitelist.contains(symbol)) {
            return rejected("open_position", argSummary, prompts.get(ctx.lang(), "trader.reject.symbolNotWhitelisted",
                    Map.of("whitelist", symbolWhitelist, "given", String.valueOf(symbol))));
        }
        try {
            BigDecimal mark = markPrice.apply(symbol);
            Account acct = account();
            BigDecimal equity = simTradeClient.getEquity(simUserId);
            String reject = opener.validate(req, acct, equity, mark);
            if (reject != null) {
                // rejected() 自己入轨迹，不许再包一层 record——否则拒绝动作双计
                return rejected("open_position", argSummary, reject);
            }
            return openResult(argSummary, opener.execute(req, acct, equity, mark));
        } catch (Exception e) {
            return fail("open_position", argSummary, e);
        }
    }

    /**
     * 开仓回执：每档都成了照常回；部分成功、结果未知带状态前缀；整批被 sim 拒且什么都没留下的按失败回。
     */
    private String openResult(ObjectNode args, ObjectNode receipt) {
        String status = receipt.path("status").asString();
        if (OK.equals(status)) {
            return ok("open_position", args, receipt);
        }
        String reason = receipt.path("reason").asString();
        if (REJECTED.equals(status)) {
            record(action("open_position", args).put("status", ERROR).put("error", reason));
            return replyPrefix(ERROR) + reason;
        }
        ObjectNode a = action("open_position", args).put("status", status).put("error", reason);
        a.set("result", receipt);
        record(a);
        return replyPrefix(status) + MAPPER.writeValueAsString(receipt);
    }

    @Tool(name = "close_position")
    public String closePosition(@ToolParam(description = "Position id from the [Account] block in your opening message (or get_account)") long positionId,
                                @ToolParam(description = "Quantity in coins to close") double quantity,
                                @ToolParam(description = "One sentence: why close now") String reason) {
        ObjectNode args = MAPPER.createObjectNode()
                .put("positionId", positionId)
                .put("quantity", quantity)
                .put("reason", reason);
        if (roundExpired()) {
            return expired("close_position", args);
        }
        try {
            // 先查仓位：拒因与 sl/tp 工具同一句，留痕也要靠它的币向找计划
            FuturesPositionDTO pos = findPosition(positionId);
            if (pos == null) {
                return rejected("close_position", args, prompts.get(ctx.lang(), "trader.reject.positionIdNotFound"));
            }
            FuturesCloseRequest req = new FuturesCloseRequest();
            req.setPositionId(positionId);
            req.setQuantity(BigDecimal.valueOf(quantity));
            req.setOrderType("MARKET");
            req.setClientRequestId(UUID.randomUUID().toString());
            FuturesOrderResponse resp = SimOrderRetry.send(() -> simTradeClient.closePosition(simUserId, req));
            // 平掉的数量进修订史，理由是模型给的；全平后计划到下轮开头对账归档
            revisePlan(pos.getSymbol(), pos.getSide(), prompts.get(ctx.lang(), "trader.revise.close"),
                    BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString(), reason);
            return ok("close_position", args, MAPPER.writeValueAsString(resp));
        } catch (SimOrderRetry.UnknownOutcome e) {
            return unknown("close_position", args, e);
        } catch (Exception e) {
            return fail("close_position", args, e);
        }
    }

    @Tool(name = "reverse_position")
    public String reversePosition(
            @ToolParam(description = "Existing position id to close entirely") long positionId,
            @ToolParam(description = "NEW opposite position size in coins") double quantity,
            @ToolParam(description = "NEW position leverage, inside the configured range") int leverage,
            @ToolParam(description = "NEW position stop-loss price, REQUIRED") Double stopLossPrice,
            @ToolParam(description = "NEW position take-profit rungs, optional, up to 3 {price, percent}", required = false) List<TargetRung> takeProfits,
            @ToolParam(description = "NEW thesis: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER") String playType,
            @ToolParam(description = "Concrete market evidence for the new trade and why reverse now") String signalsUsed,
            @ToolParam(description = "Market condition that would invalidate the NEW thesis") String invalidationCondition) {
        ObjectNode args = MAPPER.createObjectNode().put("positionId", positionId)
                .put("quantity", quantity).put("leverage", leverage).put("stopLossPrice", stopLossPrice)
                .put("playType", playType).put("signalsUsed", signalsUsed)
                .put("invalidationCondition", invalidationCondition);
        args.set("takeProfits", MAPPER.valueToTree(takeProfits));
        ObjectNode legs = MAPPER.createObjectNode();
        ObjectNode activeLeg = null;
        boolean closed = false;
        try {
            if (roundExpired()) {
                return expired("reverse_position", args);
            }
            if (!openingAllowed.getAsBoolean()) {
                return rejected("reverse_position", args, prompts.get(ctx.lang(), "trader.reject.openingStage"));
            }
            Account before = account();
            FuturesPositionDTO pos = before.positions().stream()
                    .filter(p -> p.getId() != null && p.getId() == positionId).findFirst().orElse(null);
            if (pos == null) {
                return rejected("reverse_position", args, prompts.get(ctx.lang(), "trader.reject.positionIdNotFound"));
            }
            TradeGuard.OpenReq req = new TradeGuard.OpenReq(pos.getSymbol(), "LONG".equals(pos.getSide()) ? "SHORT" : "LONG",
                    List.of(new EntryRung(BigDecimal.valueOf(quantity), null)), leverage,
                    stopLossPrice == null ? null : BigDecimal.valueOf(stopLossPrice),
                    takeProfits == null ? List.of() : takeProfits, playType, signalsUsed, invalidationCondition);
            args.put("symbol", req.symbol()).put("side", req.side());
            // 预演平仓：扣掉旧仓，和平仓时 sim 会一起撤掉的同方向分组入场单
            Account afterClose = new Account(before.positions().stream()
                    .filter(p -> !Long.valueOf(positionId).equals(p.getId())).toList(), before.pending().stream()
                    .filter(o -> !(pos.getSymbol().equals(o.getSymbol())
                            && ("OPEN_" + pos.getSide()).equals(o.getOrderSide()) && o.getGroupId() != null)).toList());
            String reject = opener.validate(req, afterClose, simTradeClient.getEquity(simUserId), markPrice.apply(req.symbol()));
            if (reject != null) {
                return rejected("reverse_position", args, reject);
            }
            if (roundExpired()) {
                return expired("reverse_position", args);
            }
            ObjectNode close = action("close_position", MAPPER.createObjectNode().put("positionId", positionId)
                    .put("quantity", pos.getQuantity()).put("reason", signalsUsed));
            ObjectNode open = action("open_position", openArgs(req)).put("status", NOT_ATTEMPTED);
            legs.set("close", close);
            legs.set("open", open);
            activeLeg = close;
            FuturesCloseRequest closeReq = new FuturesCloseRequest();
            closeReq.setPositionId(positionId);
            // quantity 留空：sim 在仓位锁内按实际数量全平。
            closeReq.setOrderType("MARKET");
            closeReq.setClientRequestId(UUID.randomUUID().toString());
            FuturesOrderResponse receipt = SimOrderRetry.send(() -> simTradeClient.closePosition(simUserId, closeReq));
            close.set("result", MAPPER.valueToTree(receipt));
            if ("LIQUIDATED".equals(receipt.getStatus())) {
                close.put("status", LIQUIDATED);
                return reverseResult(args, legs, PARTIAL,prompts.get(ctx.lang(), "trader.reject.reverseLiquidated"));
            }
            close.put("status", OK);
            closed = true;
            revisePlan(pos.getSymbol(), pos.getSide(), prompts.get(ctx.lang(), "trader.revise.close"),
                    receipt.getQuantity().stripTrailingZeros().toPlainString(), signalsUsed);
            activeLeg = open;
            Account after = account();
            BigDecimal mark = markPrice.apply(req.symbol());
            // 平完再查一次：护栏按平完之后的权益算占比
            BigDecimal equity = simTradeClient.getEquity(simUserId);
            reject = opener.validate(req, after, equity, mark);
            if (reject == null && roundExpired()) {
                reject = prompts.get(ctx.lang(), "trader.reject.expired");
            }
            if (reject != null) {
                open.put("status", REJECTED).put("rejected", reject);
                return reverseResult(args, legs, PARTIAL,reject);
            }
            ObjectNode opened = opener.execute(req, after, equity, mark);
            open.set("result", opened);
            String openStatus = opened.path("status").asString();
            open.put("status", openStatus);
            return reverseResult(args, legs, OK.equals(openStatus) ? OK : UNKNOWN.equals(openStatus) ? UNKNOWN : PARTIAL,
                    opened.path("reason").asString(null));
        } catch (SimOrderRetry.UnknownOutcome e) {
            String cause = SimTradeClient.describe(e, messages, ctx.lang());
            String msg = prompts.get(ctx.lang(), "trader.reject.unknown",
                    Map.of("cause", cause.substring(0, Math.min(cause.length(), 200))));
            activeLeg.put("status", UNKNOWN).put("error", msg);
            return reverseResult(args, legs, UNKNOWN, msg);
        } catch (Exception e) {
            if (activeLeg == null) {
                return fail("reverse_position", args, e);
            }
            String msg = SimTradeClient.describe(e, messages, ctx.lang());
            msg = msg.substring(0, Math.min(msg.length(), 300));
            activeLeg.put("status", ERROR).put("error", msg);
            return reverseResult(args, legs, closed ? PARTIAL : ERROR, msg);
        }
    }

    /** 一次反手的完整开平仓回执。 */
    private String reverseResult(ObjectNode args, ObjectNode legs, String status, String error) {
        ObjectNode a = action("reverse_position", args).put("status", status);
        a.set("result", legs);
        if (error != null) {
            a.put("error", error);
        }
        record(a);
        String prefix = OK.equals(status) ? "" : replyPrefix(status);
        return prefix + MAPPER.writeValueAsString(a);
    }

    @Tool(name = "set_stop_loss")
    public String setStopLoss(@ToolParam(description = "Position id from the [Account] block in your opening message (or get_account)") long positionId,
                              @ToolParam(description = "New stop-loss price") double stopLossPrice,
                              @ToolParam(description = "Why you move the stop now, e.g. 'price +2R, lock breakeven'") String reason) {
        ObjectNode args = MAPPER.createObjectNode()
                .put("positionId", positionId)
                .put("stopLossPrice", stopLossPrice)
                .put("reason", reason);
        if (roundExpired()) {
            return expired("set_stop_loss", args);
        }
        try {
            FuturesPositionDTO pos = findPosition(positionId);
            if (pos == null) {
                return rejected("set_stop_loss", args, prompts.get(ctx.lang(), "trader.reject.positionIdNotFound"));
            }
            if (reason == null || reason.isBlank()) {
                return rejected("set_stop_loss", args, prompts.get(ctx.lang(), "trader.reject.stopReasonRequired"));
            }
            boolean isLong = "LONG".equals(pos.getSide());
            BigDecimal newStop = BigDecimal.valueOf(stopLossPrice);
            BigDecimal mark = markPrice.apply(pos.getSymbol());
            switch (StopOrders.move(simTradeClient, simUserId, pos, newStop, mark, orderGroup(pos), this::simReason)) {
                // 止损挂到现价另一侧，下一 tick 就是市价平仓
                case StopOrders.WrongSide _ -> {
                    return rejected("set_stop_loss", args, prompts.get(ctx.lang(), "trader.reject.stopWrongSide", Map.of(
                            "mark", mark.stripTrailingZeros().toPlainString(),
                            "rule", prompts.get(ctx.lang(), isLong ? "trader.reject.stopRuleLong" : "trader.reject.stopRuleShort"),
                            "given", newStop.stripTrailingZeros().toPlainString())));
                }
                // 止损只许收紧：放宽止损=放大风险；想给仓位更多空间说明论点已动摇，该查失效条件而不是松止损
                case StopOrders.Looser l -> {
                    return rejected("set_stop_loss", args, prompts.get(ctx.lang(), "trader.reject.stopOnlyTighter",
                            Map.of("current", l.current().stripTrailingZeros().toPlainString())));
                }
                case StopOrders.Moved m -> {
                    revisePlan(pos.getSymbol(), pos.getSide(), prompts.get(ctx.lang(), "trader.revise.moveStop"),
                            (m.from() == null ? prompts.get(ctx.lang(), "trader.revise.none") : m.from().stripTrailingZeros().toPlainString())
                                    + "→" + newStop.stripTrailingZeros().toPlainString() + syncedText(m.pending()), reason);
                    return ok("set_stop_loss", args, protectionReceipt(m.pending()));
                }
                case StopOrders.PositionFailed f -> {
                    String suffix = syncedText(f.pending()) + prompts.get(ctx.lang(), "trader.revise.positionUnconfirmed");
                    revisePlan(pos.getSymbol(), pos.getSide(), prompts.get(ctx.lang(), "trader.revise.moveStop"),
                            (f.from() == null ? prompts.get(ctx.lang(), "trader.revise.none") : f.from().stripTrailingZeros().toPlainString())
                                    + "→" + newStop.stripTrailingZeros().toPlainString() + suffix, reason);
                    return fail("set_stop_loss", args, simReason(f.cause()) + suffix);
                }
            }
        } catch (Exception e) {
            return fail("set_stop_loss", args, e);
        }
    }

    @Tool(name = "set_take_profit")
    public String setTakeProfit(@ToolParam(description = "Position id from the [Account] block in your opening message (or get_account)") long positionId,
                                @ToolParam(description = "The whole new set of take-profit rungs, each {price, percent}; an empty array removes them all") List<TargetRung> takeProfits,
                                @ToolParam(description = "Why you change the targets now, e.g. 'trend accelerating, extend to next resistance'") String reason) {
        List<TargetRung> rungs = takeProfits == null ? List.of() : takeProfits;
        ObjectNode args = MAPPER.createObjectNode()
                .put("positionId", positionId)
                .put("reason", reason);
        args.set("takeProfits", MAPPER.valueToTree(rungs));
        if (roundExpired()) {
            return expired("set_take_profit", args);
        }
        FuturesPositionDTO pos = null;
        String partial = "";
        try {
            pos = findPosition(positionId);
            if (pos == null) {
                return rejected("set_take_profit", args, prompts.get(ctx.lang(), "trader.reject.positionIdNotFound"));
            }
            if (reason == null || reason.isBlank()) {
                return rejected("set_take_profit", args, prompts.get(ctx.lang(), "trader.reject.targetReasonRequired"));
            }
            String invalid = TradeGuard.validateTargets(rungs, prompts, ctx.lang());
            if (invalid != null) {
                return rejected("set_take_profit", args, invalid);
            }
            boolean isLong = "LONG".equals(pos.getSide());
            if (!rungs.isEmpty()) {
                // 哪一档站到现价另一侧都是秒触发，等于换了个名字的市价平仓
                BigDecimal mark = markPrice.apply(pos.getSymbol());
                for (TargetRung rung : rungs) {
                    if (isLong ? rung.price().compareTo(mark) <= 0 : rung.price().compareTo(mark) >= 0) {
                        return rejected("set_take_profit", args, prompts.get(ctx.lang(), "trader.reject.targetWrongSide", Map.of(
                                "mark", mark.stripTrailingZeros().toPlainString(),
                                "rule", prompts.get(ctx.lang(), isLong ? "trader.reject.targetRuleLong" : "trader.reject.targetRuleShort"),
                                "given", rung.price().stripTrailingZeros().toPlainString())));
                    }
                }
                invalid = TradeGuard.targetQuantities(rungs, pos.getQuantity(), pos.getSymbol(), prompts, ctx.lang());
                if (invalid != null) {
                    return rejected("set_take_profit", args, invalid);
                }
            }
            // 同组还挂着的档：每档按自己的数量折，哪一档折不够步长就整体拒，一张单都不动
            List<FuturesOrderResponse> pending = PendingProtection.orders(simTradeClient, simUserId,
                    pos.getSymbol(), pos.getSide(), orderGroup(pos));
            for (FuturesOrderResponse order : pending) {
                invalid = TradeGuard.targetQuantities(rungs, order.getQuantity(), pos.getSymbol(), prompts, ctx.lang());
                if (invalid != null) {
                    return rejected("set_take_profit", args, invalid);
                }
            }
            List<PendingProtection.Change> changes = new ArrayList<>();
            for (FuturesOrderResponse order : pending) {
                List<BigDecimal> quantities = TargetRung.quantities(rungs, order.getQuantity(), pos.getSymbol());
                List<FuturesOpenRequest.TakeProfit> targets = new ArrayList<>();
                for (int i = 0; i < rungs.size(); i++) {
                    FuturesOpenRequest.TakeProfit target = new FuturesOpenRequest.TakeProfit();
                    target.setPrice(rungs.get(i).price());
                    target.setQuantity(quantities.get(i));
                    targets.add(target);
                }
                FuturesOrderProtectionRequest protection = new FuturesOrderProtectionRequest();
                protection.setTakeProfits(targets);
                changes.add(PendingProtection.update(simTradeClient, simUserId, order, protection, order.getTakeProfits(),
                        this::simReason));
            }
            // 挂单处理完，再读持仓现量折算止盈。
            if (PendingProtection.count(changes, PendingProtection.UPDATED) > 0) {
                partial = syncedText(changes) + prompts.get(ctx.lang(), "trader.revise.positionUnconfirmed");
            }
            FuturesPositionDTO current = findPosition(positionId);
            invalid = current == null ? prompts.get(ctx.lang(), "trader.reject.positionIdNotFound")
                    : TradeGuard.targetQuantities(rungs, current.getQuantity(), current.getSymbol(), prompts, ctx.lang());
            if (invalid != null) {
                return partial.isEmpty() ? rejected("set_take_profit", args, invalid)
                        : targetPositionFailed(pos, rungs, reason, args, invalid, partial);
            }
            List<BigDecimal> rungQty = TargetRung.quantities(rungs, current.getQuantity(), current.getSymbol());
            List<FuturesTakeProfitRequest.TakeProfitItem> items = new ArrayList<>();
            for (int i = 0; i < rungQty.size(); i++) {
                FuturesTakeProfitRequest.TakeProfitItem item = new FuturesTakeProfitRequest.TakeProfitItem();
                item.setPrice(rungs.get(i).price());
                item.setQuantity(rungQty.get(i));
                items.add(item);
            }
            FuturesTakeProfitRequest req = new FuturesTakeProfitRequest();
            req.setPositionId(positionId);
            req.setTakeProfits(items);
            simTradeClient.setTakeProfit(simUserId, req);
            revisePlan(pos.getSymbol(), pos.getSide(), prompts.get(ctx.lang(), "trader.revise.moveTarget"),
                    targetsText(TargetRung.of(current)) + "→" + targetsText(rungs) + syncedText(changes), reason);
            return ok("set_take_profit", args, protectionReceipt(changes));
        } catch (Exception e) {
            return partial.isEmpty() ? fail("set_take_profit", args, e)
                    : targetPositionFailed(pos, rungs, reason, args, simReason(e), partial);
        }
    }

    /** 同组挂单改了、仓位那步没成：记修订，按失败回，都带上挂单改了几档 */
    private String targetPositionFailed(FuturesPositionDTO pos, List<TargetRung> rungs, String reason, ObjectNode args,
                                        String cause, String partial) {
        revisePlan(pos.getSymbol(), pos.getSide(), prompts.get(ctx.lang(), "trader.revise.moveTarget"),
                targetsText(rungs) + partial, reason);
        return fail("set_take_profit", args, cause + partial);
    }

    private String orderGroup(FuturesPositionDTO pos) {
        AiTraderPlan plan = planStore.liveFor(ctx.traderId(), ctx.roundNo(), pos.getSymbol(), pos.getSide());
        return plan == null ? null : plan.getOrderGroup();
    }

    private String syncedText(List<PendingProtection.Change> changes) {
        return PendingProtection.summary(changes, prompts, ctx.lang());
    }

    private static String protectionReceipt(List<PendingProtection.Change> changes) {
        ObjectNode receipt = MAPPER.createObjectNode().put("ok", true);
        receipt.set("pending", MAPPER.valueToTree(changes));
        return MAPPER.writeValueAsString(receipt);
    }

    /** 修订史里的止盈档写法；一档没有写"无" */
    private String targetsText(List<TargetRung> rungs) {
        return rungs.isEmpty() ? prompts.get(ctx.lang(), "trader.revise.none") : TargetRung.text(rungs);
    }

    @Tool(name = "write_plan")
    public String writePlan(@ToolParam(description = "Position id from the [Account] block in your opening message (or get_account)") long positionId,
                            @ToolParam(description = "Thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER") String playType,
                            @ToolParam(description = "One sentence citing concrete data behind holding this position") String signalsUsed,
                            @ToolParam(description = "Market condition that proves this thesis wrong (NOT a PnL number)") String invalidationCondition) {
        ObjectNode args = MAPPER.createObjectNode()
                .put("positionId", positionId)
                .put("playType", playType)
                .put("signalsUsed", signalsUsed)
                .put("invalidationCondition", invalidationCondition);
        try {
            FuturesPositionDTO pos = findPosition(positionId);
            if (pos == null) {
                return rejected("write_plan", args, prompts.get(ctx.lang(), "trader.reject.positionIdNotFound"));
            }
            if (planStore.liveFor(ctx.traderId(), ctx.roundNo(), pos.getSymbol(), pos.getSide()) != null) {
                return rejected("write_plan", args,
                        prompts.get(ctx.lang(), "trader.reject.planAlreadyExists"));
            }
            if (playType == null || !TradeGuard.PLAY_TYPES.contains(playType)) {
                return rejected("write_plan", args, prompts.get(ctx.lang(), "trader.guard.playTypeInvalid",
                        Map.of("types", TradeGuard.PLAY_TYPES)));
            }
            if (invalidationCondition == null || invalidationCondition.isBlank()) {
                return rejected("write_plan", args,
                        prompts.get(ctx.lang(), "trader.guard.invalidationRequired"));
            }
            AiTraderPlan plan = new AiTraderPlan();
            plan.setTraderId(ctx.traderId());
            plan.setRoundNo(ctx.roundNo());
            plan.setSymbol(pos.getSymbol());
            plan.setSide(pos.getSide());
            plan.setPlayType(playType);
            plan.setSignalsUsed(signalsUsed);
            plan.setInvalidationCondition(invalidationCondition);
            plan.setEntryPrice(pos.getEntryPrice());
            // 止损和止盈档都抄仓位现挂的单
            plan.setStopLossPrice(StopOrders.tightest(pos));
            plan.setTakeProfitsJson(TargetRung.toJson(TargetRung.of(pos)));
            // 补立也盖仓位id：入参已经 findPosition 对 sim 校验过，不是模型凭空抄的
            plan.setPositionId(pos.getId());
            // 持有时长按仓位真实开仓时间算，不是补立时刻——补立不能"清零仓龄"
            plan.setOpenedWakeTime(pos.getCreatedAt() != null
                    ? pos.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : ctx.wakeTime());
            TraderPlanStore.appendRevision(plan, ctx.wakeTime(), prompts.get(ctx.lang(), "trader.revise.fileNew"),
                    prompts.get(ctx.lang(), "trader.revise.fileNewNote"), signalsUsed);
            // 前置校验已确认无计划，补立就是新立
            planStore.open(plan, group -> simTradeClient.getGroupOrders(simUserId, group), ctx.lang());
            return ok("write_plan", args, "{\"ok\":true}");
        } catch (Exception e) {
            return fail("write_plan", args, e);
        }
    }

    /**
     * 账户当前占位快照：已成交持仓 + 未成交挂单。
     * 每次开仓都现查——一轮里模型可能连开几笔，用唤醒开头的快照会算漏。
     * 挂单必须计入：只数持仓的话，先挂三个不同币的限价单就绕过了单仓限制。
     */
    record Account(List<FuturesPositionDTO> positions, List<FuturesOrderResponse> pending) {
        List<TradeGuard.PosSnap> snaps() {
            List<TradeGuard.PosSnap> out = new ArrayList<>();
            positions.forEach(p ->
                    out.add(new TradeGuard.PosSnap(p.getSymbol(), p.getSide(), p.getLeverage(), true, BigDecimal.ZERO)));
            for (FuturesOrderResponse o : pending) {
                // orderSide 形如 OPEN_LONG / CLOSE_LONG：只有开仓挂单才占坑，平仓挂单是在减仓
                String s = o.getOrderSide();
                if (s == null || !s.startsWith("OPEN_")) {
                    continue;
                }
                out.add(new TradeGuard.PosSnap(o.getSymbol(), s.substring("OPEN_".length()), o.getLeverage(), false,
                        o.getQuantity().multiply(o.getLimitPrice()).divide(BigDecimal.valueOf(o.getLeverage()), 8, RoundingMode.HALF_UP)));
            }
            return out;
        }

        /** 同币同向的已成交仓位＝本次开仓是加仓（sim 会并入这一仓） */
        FuturesPositionDTO sameSide(String symbol, String side) {
            return positions.stream()
                    .filter(p -> p.getSymbol().equals(symbol) && p.getSide().equals(side))
                    .findFirst().orElse(null);
        }

        /** 同币同向还挂着的开仓单 */
        List<FuturesOrderResponse> pendingOpens(String symbol, String side) {
            return pending.stream()
                    .filter(o -> symbol.equals(o.getSymbol()) && ("OPEN_" + side).equals(o.getOrderSide())).toList();
        }
    }

    private Account account() {
        return new Account(simTradeClient.getAllPositions(simUserId),
                simTradeClient.getPendingOrders(simUserId, null));
    }

    /** 按 positionId 从 sim 现查持仓（工具间不共享缓存——sim 是唯一事实源）。 */
    private FuturesPositionDTO findPosition(long positionId) {
        return simTradeClient.getAllPositions(simUserId).stream()
                .filter(p -> p.getId() != null && p.getId() == positionId)
                .findFirst().orElse(null);
    }

    /**
     * 移止损/移止盈/平仓的留痕：按币向找存活计划，这是唤醒中唯一可靠的找法
     * （本轮内成交的限价仓要到下轮开头才绑上 id）。没有计划（旧仓）不强求，write_plan 是补救路径。
     * 交易已成交，留痕失败只记日志不回错——回错会诱导模型再做一次。
     */
    private void revisePlan(String symbol, String side, String type, String change, String reason) {
        try {
            AiTraderPlan plan = planStore.liveFor(ctx.traderId(), ctx.roundNo(), symbol, side);
            if (plan != null) {
                planStore.revise(plan, ctx.wakeTime(), type, change, reason);
            }
        } catch (Exception e) {
            log.warn("[TradeTools] 修订落库失败 traderId={} {} msg={}", ctx.traderId(), symbol, e.getMessage());
        }
    }

    /** 本轮预算已耗尽：唤醒回路那边正在超时作废，这时候再下单就是给下一轮留没人认领的仓位。 */
    private boolean roundExpired() {
        return System.currentTimeMillis() > ctx.deadlineMs();
    }

    private String expired(String tool, ObjectNode args) {
        return rejected(tool, args, prompts.get(ctx.lang(), "trader.reject.expired"));
    }

    /** 结果未知：绝不能当普通失败回——模型看见 ERROR 会重下一单，那就是双仓。 */
    private String unknown(String tool, ObjectNode args, SimOrderRetry.UnknownOutcome e) {
        String cause = String.valueOf(e.getCause().getMessage());
        if (cause.length() > 200) {
            cause = cause.substring(0, 200) + "…";
        }
        record(action(tool, args).put("status", UNKNOWN).put("error", cause));
        log.warn("[TradeTools] {} 结果未知 simUserId={} msg={}", tool, simUserId, cause);
        return prompts.get(ctx.lang(), "trader.reject.unknown", Map.of("cause", String.valueOf(cause)));
    }

    /** 护栏拒绝：与 open_position 的 REJECTED 同一语义，进动作轨迹，模型可修正重试。 */
    private String rejected(String tool, ObjectNode args, String reason) {
        record(action(tool, args).put("rejected", reason));
        return replyPrefix(REJECTED) + reason;
    }

    @Tool(name = "cancel_order")
    public String cancelOrder(@ToolParam(description = "One or more order ids from the [Account] block's pendingOrders in your opening message (or get_account)") List<Long> orderIds,
                              @ToolParam(description = "Why these pending orders should be cancelled") String reason) {
        ObjectNode args = MAPPER.createObjectNode().put("reason", reason);
        args.set("orderIds", MAPPER.valueToTree(orderIds));
        if (roundExpired()) {
            return expired("cancel_order", args);
        }
        if (reason == null || reason.isBlank()) {
            return rejected("cancel_order", args, prompts.get(ctx.lang(), "trader.reject.cancelReasonRequired"));
        }
        if (orderIds == null || orderIds.isEmpty()) {
            return rejected("cancel_order", args, prompts.get(ctx.lang(), "trader.reject.orderIdsRequired"));
        }
        ObjectNode receipt = MAPPER.createObjectNode();
        ArrayNode orders = receipt.putArray("orders");
        List<FuturesOrderResponse> cancelled = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (long orderId : new LinkedHashSet<>(orderIds)) {
            ObjectNode row = orders.addObject().put("orderId", orderId);
            try {
                FuturesOrderResponse response = simTradeClient.cancelOrder(simUserId, orderId);
                cancelled.add(response);
                row.put("symbol", response.getSymbol()).put("orderSide", response.getOrderSide())
                        .put("limitPrice", response.getLimitPrice()).put("quantity", response.getQuantity())
                        .put("status", "cancelled");
            } catch (Exception e) {
                String message = simReason(e);
                row.put("status", "failed").put("reason", message);
                failures.add("#" + orderId + " " + message);
            }
        }
        if (!cancelled.isEmpty()) {
            try {
                planStore.cancelEntries(ctx.traderId(), ctx.roundNo(), cancelled, reason, ctx.wakeTime(), ctx.lang());
            } catch (Exception e) {
                log.warn("[TradeTools] 撤单标档失败 traderId={}", ctx.traderId(), e);
            }
        }
        if (failures.isEmpty()) {
            return ok("cancel_order", args, receipt);
        }
        String error = String.join(" / ", failures);
        if (cancelled.isEmpty()) {
            return fail("cancel_order", args, error);
        }
        record(action("cancel_order", args).put("status", PARTIAL).put("error", error).set("result", receipt));
        return "PARTIAL: " + MAPPER.writeValueAsString(receipt);
    }

    @Tool(name = "set_watch")
    public String setWatch(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                           @ToolParam(description = "The whole new list for this symbol (at most one upward, one downward, one AT_TIME); an empty array clears it") List<WatchSpec> watches) {
        ObjectNode args = MAPPER.createObjectNode().put("symbol", symbol);
        args.set("watches", MAPPER.valueToTree(watches));
        if (roundExpired()) {
            return expired("set_watch", args);
        }
        if (symbol == null || !watchSymbols.contains(symbol)) {
            return rejected("set_watch", args, prompts.get(ctx.lang(), "trader.guard.watchSymbolInvalid",
                    Map.of("symbols", new TreeSet<>(watchSymbols))));
        }
        if (watches == null) {
            return rejected("set_watch", args, prompts.get(ctx.lang(), "trader.guard.watchListRequired"));
        }
        try {
            // 现价只有 TOUCH 条件要用，清空或只布收盘/时刻条件不碰行情接口
            BigDecimal mark = watches.stream().anyMatch(s -> s.kind() != null && s.kind().startsWith("TOUCH_"))
                    ? markPrice.apply(symbol) : null;
            String reason = TradeGuard.validateWatch(watches, mark, System.currentTimeMillis(), prompts, ctx.lang());
            if (reason != null) {
                return rejected("set_watch", args, reason);
            }
            // 预案动作对着账户查一遍，落库时带上那个仓位 / 挂单的币和方向；没带动作的条件不查账户
            List<String> presets = new ArrayList<>();
            Account acct = watches.stream().anyMatch(s -> s.action() != null) ? account() : null;
            for (WatchSpec s : watches) {
                if (s.action() == null) {
                    presets.add(null);
                    continue;
                }
                ObjectNode action = MAPPER.valueToTree(s.action());
                reason = bindAction(s.action(), acct, action);
                if (reason != null) {
                    return rejected("set_watch", args, reason);
                }
                presets.add(MAPPER.writeValueAsString(action));
            }
            watchStore.replace(ctx.traderId(), ctx.roundNo(), symbol, watches, presets, ctx.wakeTime());
            return ok("set_watch", args, "{\"ok\":true,\"armed\":" + watches.size() + "}");
        } catch (Exception e) {
            return fail("set_watch", args, e);
        }
    }

    /**
     * 预案动作指的仓位 / 挂单得在账户里，移止损的新价不能比现挂的松；查到了把币和方向写进 stored。
     * 通过返回 null，否则是拒因。新止损站在现价哪一侧到命中那一刻才知道，这里不查
     */
    private String bindAction(WatchAction a, Account acct, ObjectNode stored) {
        if (AiTraderWatch.ACTION_CANCEL_ORDER.equals(a.type())) {
            FuturesOrderResponse order = acct.pending().stream()
                    .filter(o -> a.orderId().equals(o.getOrderId())).findFirst().orElse(null);
            if (order == null) {
                return prompts.get(ctx.lang(), "trader.reject.orderIdNotFound");
            }
            stored.put("symbol", order.getSymbol()).put("side", order.getOrderSide());
            return null;
        }
        FuturesPositionDTO pos = acct.positions().stream()
                .filter(p -> a.positionId().equals(p.getId())).findFirst().orElse(null);
        if (pos == null) {
            return prompts.get(ctx.lang(), "trader.reject.positionIdNotFound");
        }
        if (AiTraderWatch.ACTION_MOVE_STOP.equals(a.type())) {
            BigDecimal tightest = StopOrders.tightest(pos);
            boolean isLong = "LONG".equals(pos.getSide());
            if (tightest != null && (isLong ? a.stopLossPrice().compareTo(tightest) < 0
                    : a.stopLossPrice().compareTo(tightest) > 0)) {
                return prompts.get(ctx.lang(), "trader.reject.stopOnlyTighter",
                        Map.of("current", tightest.stripTrailingZeros().toPlainString()));
            }
        }
        stored.put("symbol", pos.getSymbol()).put("side", pos.getSide());
        return null;
    }

    private static ObjectNode openArgs(TradeGuard.OpenReq req) {
        ObjectNode args = MAPPER.createObjectNode()
                .put("symbol", req.symbol())
                .put("side", req.side())
                .put("leverage", req.leverage())
                .put("stopLossPrice", req.stopLossPrice());
        args.set("entries", MAPPER.valueToTree(req.entries()));
        args.set("takeProfits", MAPPER.valueToTree(req.takeProfits()));
        return args
                .put("playType", req.playType())
                .put("signalsUsed", req.signalsUsed())
                .put("invalidationCondition", req.invalidationCondition());
    }

    private static ObjectNode action(String tool, ObjectNode args) {
        ObjectNode a = MAPPER.createObjectNode().put("tool", tool);
        if (args != null) {
            a.set("args", args);
        }
        return a;
    }

    /**
     * 动作填完整了再入轨迹，经 ReactLoop 调进来的带上这次调用的 id（callId）：
     * 同一批里两个 get_account 会并发到这儿（别的工具都在 SERIAL_TOOLS 里）
     */
    private synchronized void record(ObjectNode action) {
        if (ReactLoop.TOOL_CALL_ID.isBound()) {
            action.put("callId", ReactLoop.TOOL_CALL_ID.get());
        }
        actions.add(action);
    }

    /** 成功：结果同时写进动作轨迹（摘要）与工具返回值（全文）。 */
    private String ok(String tool, ObjectNode args, Object payload) {
        String s = payload instanceof String str ? str : MAPPER.writeValueAsString(payload);
        ObjectNode action = action(tool, args).put("status", OK);
        // 轨迹里只存摘要，防止 get_account 大 JSON 把决策行撑爆；开仓回执整条存
        if (s.length() > 1000 && !"open_position".equals(tool)) {
            action.put("result", s.substring(0, 1000) + "…");
        } else {
            try {
                action.set("result", MAPPER.readTree(s));
            } catch (Exception e) {
                action.put("result", s); // 不是 JSON 的结果原样入轨迹
            }
        }
        record(action);
        return s;
    }

    private String fail(String tool, ObjectNode args, Exception e) {
        return fail(tool, args, simReason(e));
    }

    private String fail(String tool, ObjectNode args, String msg) {
        record(action(tool, args).put("status", ERROR).put("error", msg));
        log.warn("[TradeTools] {} 失败 simUserId={} msg={}", tool, simUserId, msg);
        return replyPrefix(ERROR) + msg;
    }

    private String simReason(Exception e) {
        return simReason(e, messages, ctx.lang());
    }

    /** sim 那侧的拒因成文 */
    static String simReason(Exception e, MessageCatalog messages, AgentLang lang) {
        // sim 拒因按码查词表跟 trader 主人的语言：这句既进模型上下文又进公开时间线，
        // 跟不上语言的话英文 trader 会读到一句中文（模型立刻跟着混）
        String msg = SimTradeClient.describe(e, messages, lang);
        // 上游异常可能拖着整段响应体（曾见全市场premiumIndex数组进拒因），截断防烧token防撑爆轨迹
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }
}
