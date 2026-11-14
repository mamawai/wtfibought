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
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibquant.external.sim.SimOrderRetry;
import com.mawai.wiibquant.external.sim.SimTradeApi;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 交易工具（非 Spring bean）：每次唤醒 new 一个，绑定该 trader 的 sim 子账户与白名单。
 * 所有调用（含被 TradeGuard 拒绝的）都记入 actions 列表——决策日志的动作轨迹，
 * 拒绝原因原样返回给模型，模型可自行修正重试。
 */
@Slf4j
public class TradeTools {

    /**
     * 唤醒上下文：计划落库与风险护栏所需的 trader 侧信息；interval＝心跳周期，开仓回执里的 ATR 按它算；
     * deadlineMs＝本轮预算耗尽的墙钟时刻。
     * lang＝这只 trader 主人的语言：拒因既回给模型也公开在竞技场时间线上，两处都得跟它走。
     */
    public record WakeCtx(long traderId, int roundNo, String interval, long boundaryTime, long deadlineMs,
                          TraderRiskConfig risk, AgentLang lang) {
    }

    /** 本类自带富记录（结果/拒因）的工具名——轨迹合并时用富记录替换轨迹收集器的轻量占位 */
    public static final Set<String> RECORDED_TOOLS = Set.of(
            "get_account", "open_position", "close_position", "reverse_position", "set_stop_loss",
            "set_take_profit", "cancel_order", "write_plan", "set_watch");

    /** 改账户状态的工具名：同一批 tool_call 里有它们就整批串行，不和别的调用交错（ReactLoop.serialTools） */
    public static final Set<String> SERIAL_TOOLS = Set.of(
            "open_position", "close_position", "reverse_position", "set_stop_loss", "set_take_profit", "write_plan", "cancel_order", "set_watch");

    private final SimTradeClient simTradeClient;
    private final long simUserId;
    private final Set<String> symbolWhitelist;
    /** 能布盯盘条件的币：平台在采的全部币，不限可交易的那几个 */
    private final Set<String> watchSymbols;
    /** 按现查持仓算权益：同一轮先平后开，护栏要按平完之后的权益算占比 */
    private final Function<List<FuturesPositionDTO>, BigDecimal> equityOf;
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
    /** 开仓那一刻的上下文快照，随计划落库 */
    private final EntryContext entryContext;
    /** 本轮是否已进入可以开仓的阶段 */
    private final BooleanSupplier openingAllowed;
    /** 本次唤醒的动作轨迹，唤醒回路收走序列化进 ai_trader_decision.actions_json */
    private final List<ObjectNode> actions = new ArrayList<>();

    public TradeTools(SimTradeClient simTradeClient, long simUserId, Set<String> symbolWhitelist, Set<String> watchSymbols,
                      Function<List<FuturesPositionDTO>, BigDecimal> equityOf, Function<String, BigDecimal> markPrice,
                      TraderPlanStore planStore, TraderWatchStore watchStore, WakeCtx ctx,
                      PromptCatalog prompts, MessageCatalog messages, EntryContext entryContext,
                      BooleanSupplier openingAllowed) {
        this.simTradeClient = simTradeClient;
        this.simUserId = simUserId;
        this.symbolWhitelist = symbolWhitelist;
        this.watchSymbols = watchSymbols;
        this.equityOf = equityOf;
        this.markPrice = markPrice;
        this.planStore = planStore;
        this.watchStore = watchStore;
        this.ctx = ctx;
        this.prompts = prompts;
        this.messages = messages;
        this.entryContext = entryContext;
        this.openingAllowed = openingAllowed;
    }

    /** 唤醒回路收轨迹：超时那条路上被中断的工具可能还在往里记，拷一份出去 */
    public synchronized List<ObjectNode> actions() {
        return List.copyOf(actions);
    }

    // 描述只客观说"返回什么"，不下行为指令：系统提示词明说账户状态每轮已注入、
    // 让把调用预算花在行情求证上，这里再写 ALWAYS check 就是两条强指令打架
    @Tool(name = "get_account", description = """
            Get your full account state: equity, available (what is still free for new orders after
            position margin and pending-order reservations), wallet balance, open positions (with id,
            side, quantity, entry price, leverage, unrealized PnL, liquidation price, current
            stop-loss/take-profit) and pending limit orders. This same state is already injected into
            your opening message every round (the [Account] block), so you normally do not need to call
            this; use it to confirm changes after your trades or fills that occurred while you were checking data.""")
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
        // 权益与开场白同一口径；available 是 sim 全仓账本的下单额度，钱包余额里还躺着已占用的保证金，不能当可用
        BigDecimal equity = equityOf.apply(positions);
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
            addAccountPlan(row, p.getSymbol(), p.getSide());
            ps.add(row);
        }
        out.set("positions", ps);
        List<FuturesOrderResponse> pending = simTradeClient.getPendingOrders(simUserId, null);
        ArrayNode orders = MAPPER.valueToTree(pending);
        for (int i = 0; i < pending.size(); i++) {
            FuturesOrderResponse order = pending.get(i);
            ObjectNode row = (ObjectNode) orders.get(i);
            row.put("lossAtStopPctOfEquity", pctOfEquity(StopOrders.lossAtStops(order), equity));
            if (order.getOrderSide() != null && order.getOrderSide().startsWith("OPEN_")) {
                addAccountPlan(row, order.getSymbol(), order.getOrderSide().substring("OPEN_".length()));
            }
        }
        out.set("pendingOrders", orders);
        out.put("lossIfPendingFillPctOfEquity", pctOfEquity(pending.stream().map(StopOrders::lossAtStops)
                .reduce(BigDecimal.ZERO, BigDecimal::add), equity));
        return MAPPER.writeValueAsString(out);
    }

    /** 持仓行、挂单行带上所属计划的轻量版 */
    private void addAccountPlan(ObjectNode row, String symbol, String side) {
        AiTraderPlan plan = planStore.liveFor(ctx.traderId(), ctx.roundNo(), symbol, side);
        if (plan == null) {
            return;
        }
        ObjectNode json = MAPPER.createObjectNode()
                .put("playType", plan.getPlayType())
                .put("invalidationCondition", plan.getInvalidationCondition())
                .put("entryPrice", plan.getEntryPrice())
                .put("originalStop", plan.getStopLossPrice());
        if (plan.getEntriesJson() != null) {
            json.set("entries", PlanEntry.summary(plan));
        }
        row.set("plan", json);
    }

    private static BigDecimal pctOfEquity(BigDecimal amount, BigDecimal equity) {
        return amount.multiply(BigDecimal.valueOf(100)).divide(equity, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? null : new BigDecimal(String.valueOf(v));
    }

    @Tool(name = "open_position", description = """
            Open a futures position on your sim account (cross margin) using entries: 1–5 rungs
            {quantity, limitPrice}. Omit limitPrice for a market rung; at most one market rung per call.
            Limits go first, market last. Limits must rest within 5% of mark (LONG below, SHORT above).
            Each rung must meet quantity steps and minimum notional. Leverage must be inside your owner's
            range and match existing positions/orders. Without a filled same-side position, this batch plus
            same-side pending margin must fit the equity cap; filled adds are exempt. Leverage tiers are
            prechecked on existing position + same-side pending + this batch's notional.
            stopLossPrice is REQUIRED, beyond every entry. Optional takeProfits: up to 3 {price, percent}
            rungs on the favorable side of every entry, percents sum to at most 100; quantities must meet
            the step for each entry. Omit takeProfits to place no targets.
            playType: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER.
            signalsUsed cites concrete data; invalidationCondition is the market condition that disproves
            the thesis, not a PnL number. Entries, stop, targets and thesis are recorded in one plan.
            The receipt lists each rung. If only part of the batch went through, the reply starts with
            PARTIAL: and the rungs that filled stay. If a rung's outcome is unknown it starts with UNKNOWN: —
            reconcile with get_account before resubmitting. If the whole batch is refused, rungs already
            resting are withdrawn and the reply is an ERROR with the reason. facts.batch estimates quantity, weighted
            entry, notional, round-trip fees, stop distance (% and heartbeat ATR14), loss at stop and equity
            risk; targets add estimated gain and reward/risk. facts.sameSide estimates the total loss at
            current stops for this symbol and direction. These are scenarios, excluding exit slippage.""")
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
        BigDecimal mark;
        try {
            mark = markPrice.apply(symbol);
        } catch (Exception e) {
            return fail("open_position", argSummary, e);
        }
        Account acct = account();
        BigDecimal equity = equityOf.apply(acct.positions());
        try {
            String reject = validateOpen(req, acct, equity, mark);
            if (reject != null) {
                // rejected() 自己入轨迹，不许再包一层 record——否则拒绝动作双计
                return rejected("open_position", argSummary, reject);
            }
            return openResult(argSummary, executeOpen(req, acct, equity, mark));
        } catch (Exception e) {
            return fail("open_position", argSummary, e);
        }
    }

    /**
     * 开仓回执：每档都成了照常回；部分成功、结果未知带状态前缀；整批被 sim 拒且什么都没留下的按失败回。
     * 前缀和动作行顶层的 error 是过程回放、时间线认状态的地方
     */
    private String openResult(ObjectNode args, ObjectNode receipt) {
        String status = receipt.path("status").asString();
        if ("ok".equals(status)) {
            return ok("open_position", args, receipt);
        }
        String reason = receipt.path("reason").asString();
        if ("rejected".equals(status)) {
            record(action("open_position", args).put("status", "error").put("error", reason));
            return "ERROR: " + reason;
        }
        ObjectNode a = action("open_position", args).put("status", status).put("error", reason);
        a.set("result", receipt);
        record(a);
        return status.toUpperCase(Locale.ROOT) + ": " + MAPPER.writeValueAsString(receipt);
    }

    /** 普通开仓和反手新仓走同一套护栏。 */
    private String validateOpen(TradeGuard.OpenReq req, Account acct, BigDecimal equity, BigDecimal mark) {
        String reject = TradeGuard.validateOpen(req, equity, mark, symbolWhitelist, ctx.risk(), acct.snaps(),
                prompts, ctx.lang());
        if (reject != null) {
            return reject;
        }
        FuturesPositionDTO sameSide = acct.sameSide(req.symbol(), req.side());
        // 止盈价位数按 sim 并单后的算：仓位上还摊得到数量的旧价位，加本次的新价位，同价算一档
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
        for (FuturesOrderResponse o : acct.pending()) {
            if (req.symbol().equals(o.getSymbol()) && ("OPEN_" + req.side()).equals(o.getOrderSide())) {
                total = total.add(o.getQuantity().multiply(o.getLimitPrice()));
            }
        }
        List<SimTradeApi.Bracket> brackets = simTradeClient.getBrackets(List.of(req.symbol())).get(req.symbol());
        return TradeGuard.validateBracket(total, req.leverage(), brackets, prompts, ctx.lang());
    }

    /**
     * 普通开仓与反手共用：逐档下单、立计划、出回执。先挂限价档，最后下市价档。
     * 回执的 status：ok 每档都成了；partial 有档被拒、已成交的留着；unknown 有档结果未知；rejected 被拒且什么都没留下。
     */
    private ObjectNode executeOpen(TradeGuard.OpenReq req, Account acct, BigDecimal equity, BigDecimal mark) {
        FuturesPositionDTO sameSide = acct.sameSide(req.symbol(), req.side());
        boolean coversPlan = sameSide != null || acct.pendingOpen(req.symbol(), req.side());
        BigDecimal entryRef = req.entryPrice(mark);
        AiTraderPlan live = coversPlan ? planStore.liveFor(ctx.traderId(), ctx.roundNo(), req.symbol(), req.side()) : null;
        // 加仓沿用这份计划的订单组，首开新起一组
        String groupId = live != null && live.getOrderGroup() != null ? live.getOrderGroup() : UUID.randomUUID().toString();
        // 下单前的费用与风险估算。
        ObjectNode batch = OpenFacts.of(req, mark, equity);
        batch.set("entries", MAPPER.valueToTree(req.entries()));
        ArrayNode results = MAPPER.createArrayNode();
        List<PlanEntry> planned = new ArrayList<>();
        String status = "ok";
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
            if (!"ok".equals(status)) {
                result.put("status", "not_attempted");
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
                // 这档可能已经挂上或成交：不撤别的档，记成未知留给对账认领
                status = "unknown";
                error = prompts.get(ctx.lang(), "trader.reject.orderUnknown");
                saved.setStatus("UNKNOWN");
                planned.add(saved);
                result.put("status", "unknown");
                continue;
            } catch (Exception e) {
                String reason = simReason(e);
                result.put("status", "rejected").put("reason", reason);
                int withdrawn = withdraw(planned, results, reason);
                if (planned.stream().anyMatch(p -> !"CANCELLED".equals(p.getStatus()))) {
                    status = "partial";
                    error = reason;
                } else {
                    status = "rejected";
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
        if (!"rejected".equals(status)) {
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
                log.info("[TradeTools] 分档撤回未成功 orderId={} msg={}", entry.getOrderId(), e.getMessage());
            }
        }
        return withdrawn;
    }

    /** 这个币这个方向合计：下单前已有的仓位和挂单按现挂止损，加上本次留下的各档，全部被打掉的价差亏损 */
    private ObjectNode sameSideFacts(TradeGuard.OpenReq req, Account acct, List<PlanEntry> planned,
                                     BigDecimal equity, BigDecimal mark) {
        BigDecimal loss = BigDecimal.ZERO;
        for (FuturesPositionDTO p : acct.positions()) {
            if (req.symbol().equals(p.getSymbol()) && req.side().equals(p.getSide())) {
                loss = loss.add(StopOrders.lossAtStops(p));
            }
        }
        for (FuturesOrderResponse o : acct.pending()) {
            if (req.symbol().equals(o.getSymbol()) && ("OPEN_" + req.side()).equals(o.getOrderSide())) {
                loss = loss.add(StopOrders.lossAtStops(o));
            }
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
            plan.setOpenedWakeTime(ctx.boundaryTime());
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
            log.warn("[TradeTools] 计划落库失败 traderId={} {} msg={}", ctx.traderId(), req.symbol(), e.getMessage());
        }
    }

    /** 抓开仓那一刻的上下文写进计划的 entry_ctx_json；抓不到只记 warn */
    void captureEntryContext(Long planId, TradeGuard.OpenReq req, BigDecimal entryRef, long nowMs) {
        try {
            planStore.setEntryContext(planId, entryContext.capture(req.symbol(), req.orderType(), entryRef,
                    req.stopLossPrice(), req.leverage(), nowMs));
        } catch (Exception e) {
            log.warn("[TradeTools] 入场上下文抓取失败 traderId={} {} msg={}", ctx.traderId(), req.symbol(), e.getMessage());
        }
    }

    @Tool(name = "close_position", description = """
            Close (part of) an open position by positionId (from the [Account] block in your opening message, or get_account), at market price.
            quantity: coins to close; pass the full position quantity to close it entirely.
            reason: one sentence on why you are closing now.
            A full close also cancels all unfilled grouped entry orders for this symbol and direction. Partial closes or partial targets leave them resting.""")
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

    @Tool(name = "reverse_position", description = """
            Fully close positionId at MARKET, then open the opposite side of the same symbol at MARKET.
            quantity and leverage describe the NEW position; quantity need not match the old position.
            stopLossPrice is REQUIRED for the new side. Optional takeProfits and required playType, signalsUsed and
            invalidationCondition have the same meaning as open_position; signalsUsed also explains why
            you are reversing now. The symbol and opposite side are inferred from the current position.
            The new order is checked against the same opening rules before closing, then checked again
            using the refreshed account and mark price after closing. Existing same-side exposure is
            handled like open_position (an add). This is two orders, not an atomic transaction:
            partial means execution was incomplete; inspect both leg receipts. unknown means
            an order may have filled, so reconcile with get_account before placing another order.
            The receipt includes each leg and the new order's facts; opening files a plan.
            A full close also cancels all unfilled grouped entry orders for the old symbol and direction. Watch conditions remain in place.""")
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
            // 预检占位扣掉原仓，权益按完整账户计算。
            Account afterClose = new Account(before.positions().stream()
                    .filter(p -> !Long.valueOf(positionId).equals(p.getId())).toList(), before.pending());
            String reject = validateOpen(req, afterClose, equityOf.apply(before.positions()), markPrice.apply(req.symbol()));
            if (reject != null) {
                return rejected("reverse_position", args, reject);
            }
            if (roundExpired()) {
                return expired("reverse_position", args);
            }
            ObjectNode close = action("close_position", MAPPER.createObjectNode().put("positionId", positionId)
                    .put("quantity", pos.getQuantity()).put("reason", signalsUsed));
            ObjectNode open = action("open_position", openArgs(req)).put("status", "not_attempted");
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
                close.put("status", "liquidated");
                return reverseResult(args, legs, "partial", prompts.get(ctx.lang(), "trader.reject.reverseLiquidated"));
            }
            close.put("status", "ok");
            closed = true;
            revisePlan(pos.getSymbol(), pos.getSide(), prompts.get(ctx.lang(), "trader.revise.close"),
                    receipt.getQuantity().stripTrailingZeros().toPlainString(), signalsUsed);
            activeLeg = open;
            Account after = account();
            BigDecimal mark = markPrice.apply(req.symbol());
            BigDecimal equity = equityOf.apply(after.positions());
            reject = validateOpen(req, after, equity, mark);
            if (reject == null && roundExpired()) {
                reject = prompts.get(ctx.lang(), "trader.reject.expired");
            }
            if (reject != null) {
                open.put("status", "rejected").put("rejected", reject);
                return reverseResult(args, legs, "partial", reject);
            }
            ObjectNode opened = executeOpen(req, after, equity, mark);
            open.set("result", opened);
            String openStatus = opened.path("status").asString();
            open.put("status", openStatus);
            return reverseResult(args, legs, "ok".equals(openStatus) ? "ok" : "unknown".equals(openStatus) ? "unknown" : "partial",
                    opened.path("reason").asString(null));
        } catch (SimOrderRetry.UnknownOutcome e) {
            String cause = SimTradeClient.describe(e, messages, ctx.lang());
            String msg = prompts.get(ctx.lang(), "trader.reject.unknown",
                    Map.of("cause", cause.substring(0, Math.min(cause.length(), 200))));
            activeLeg.put("status", "unknown").put("error", msg);
            return reverseResult(args, legs, "unknown", msg);
        } catch (Exception e) {
            if (activeLeg == null) {
                return fail("reverse_position", args, e);
            }
            String msg = SimTradeClient.describe(e, messages, ctx.lang());
            msg = msg.substring(0, Math.min(msg.length(), 300));
            activeLeg.put("status", "error").put("error", msg);
            return reverseResult(args, legs, closed ? "partial" : "error", msg);
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
        String prefix = "ok".equals(status) ? "" : status.toUpperCase(java.util.Locale.ROOT) + ": ";
        return prefix + MAPPER.writeValueAsString(a);
    }

    @Tool(name = "set_stop_loss", description = """
            Replace the stop-loss of an open position (positionId from the [Account] block in your opening message, or get_account). The new stop
            always covers the WHOLE position — you do not pass a quantity. Guard: tighten only —
            LONG stops may only move UP, SHORT stops only DOWN (relative to the current stop); it cannot
            sit on the far side of the current price (it would fire at once). To exit, use close_position.
            reason is REQUIRED and becomes part of the position's public plan revision history.
            First update eligible PENDING entries in the same plan group, then the position. An entry keeps its own stop if the new stop is inside its entry price or is not tighter than its own stop; the receipt lists updated, retained and failed entries. With no filled position yet, cancel the entries with reasons and place them again.""")
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
            }
        } catch (Exception e) {
            return fail("set_stop_loss", args, e);
        }
    }

    @Tool(name = "set_take_profit", description = """
            Replace ALL take-profit orders of an open position (positionId from the [Account] block in your opening
            message, or get_account) with a new set of rungs: up to 3, each a price plus the percent of the current
            position it closes (percents sum to at most 100; whatever is left has no take-profit order). An empty
            array removes every take-profit order. Rungs may move nearer or farther; each must still be on the
            profit side of the current price (LONG above, SHORT below), otherwise it would fire at once — to leave
            now, use close_position.
            reason is REQUIRED (public plan revision history).
            Update PENDING entries in the same plan group first, then reread the position size and replace its targets. Each entry uses its own quantity; any target below one quantity step rejects the request before changes. An empty array clears targets on both pending entries and the position. With no filled position yet, cancel the entries with reasons and place them again.""")
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
        try {
            FuturesPositionDTO pos = findPosition(positionId);
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
            pos = findPosition(positionId);
            if (pos == null) {
                return rejected("set_take_profit", args, prompts.get(ctx.lang(), "trader.reject.positionIdNotFound"));
            }
            invalid = TradeGuard.targetQuantities(rungs, pos.getQuantity(), pos.getSymbol(), prompts, ctx.lang());
            if (invalid != null) {
                return rejected("set_take_profit", args, invalid);
            }
            List<BigDecimal> rungQty = TargetRung.quantities(rungs, pos.getQuantity(), pos.getSymbol());
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
                    targetsText(TargetRung.of(pos)) + "→" + targetsText(rungs) + syncedText(changes), reason);
            return ok("set_take_profit", args, protectionReceipt(changes));
        } catch (Exception e) {
            return fail("set_take_profit", args, e);
        }
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

    @Tool(name = "write_plan", description = """
            Backfill a trading plan for an open position that has NO plan record (positionId from the
            [Account] block in your opening message, or get_account). The stop and the take-profit rungs are
            copied from the position's current orders.
            Rejected if the position already has a plan — plans are immutable; the only
            legal ways to change a thesis are adding to the position or closing and reopening.""")
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
                    : ctx.boundaryTime());
            TraderPlanStore.appendRevision(plan, ctx.boundaryTime(), prompts.get(ctx.lang(), "trader.revise.fileNew"),
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
    private record Account(List<FuturesPositionDTO> positions, List<FuturesOrderResponse> pending) {
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

        /** 同币同向还挂着开仓单 */
        boolean pendingOpen(String symbol, String side) {
            return pending.stream()
                    .anyMatch(o -> symbol.equals(o.getSymbol()) && ("OPEN_" + side).equals(o.getOrderSide()));
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
                planStore.revise(plan, ctx.boundaryTime(), type, change, reason);
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
        record(action(tool, args).put("status", "unknown").put("error", cause));
        log.warn("[TradeTools] {} 结果未知 simUserId={} msg={}", tool, simUserId, cause);
        return prompts.get(ctx.lang(), "trader.reject.unknown", Map.of("cause", String.valueOf(cause)));
    }

    /** 护栏拒绝：与 open_position 的 REJECTED 同一语义，进动作轨迹，模型可修正重试。 */
    private String rejected(String tool, ObjectNode args, String reason) {
        record(action(tool, args).put("rejected", reason));
        return "REJECTED: " + reason;
    }

    @Tool(name = "cancel_order", description = "Cancel a pending limit order by orderId (from the [Account] block's pendingOrders in your opening message, or get_account). reason is REQUIRED and records why this entry was cancelled in the plan and its revision history.")
    public String cancelOrder(@ToolParam(description = "Order id from the [Account] block's pendingOrders in your opening message (or get_account)") long orderId,
                              @ToolParam(description = "Why this pending order should be cancelled") String reason) {
        ObjectNode args = MAPPER.createObjectNode().put("orderId", orderId).put("reason", reason);
        if (roundExpired()) {
            return expired("cancel_order", args);
        }
        try {
            if (reason == null || reason.isBlank()) {
                return rejected("cancel_order", args, prompts.get(ctx.lang(), "trader.reject.cancelReasonRequired"));
            }
            FuturesOrderResponse resp = simTradeClient.cancelOrder(simUserId, orderId);
            planStore.cancelEntry(ctx.traderId(), ctx.roundNo(), resp, reason, ctx.boundaryTime(), ctx.lang());
            return ok("cancel_order", args, MAPPER.writeValueAsString(resp));
        } catch (Exception e) {
            return fail("cancel_order", args, e);
        }
    }

    @Tool(name = "set_watch", description = """
            Replace ALL your watch conditions on one symbol. The symbol may be any coin the platform tracks, not
            only your tradable ones (e.g. BTCUSDT as the market benchmark). The platform watches them between
            wake-ups and wakes you the moment one is met: a non-routine wake-up whose opening names the condition
            and your note.
            kind: TOUCH_ABOVE / TOUCH_BELOW (mark price touches level), CLOSE_ABOVE / CLOSE_BELOW (a candle of
            period 5m/15m/1h/4h/1d closes above / below level; a close of your heartbeat period or longer lands on the
            routine wake-up at that moment and is read out there, not as a separate wake-up), AT_TIME (at `at`,
            Beijing time yyyy-MM-dd HH:mm).
            The current Beijing time is nowBeijing in the [Account] block. note: one sentence to your next self about
            what it means when this fires. At most one upward (TOUCH_ABOVE / CLOSE_ABOVE), one downward
            (TOUCH_BELOW / CLOSE_BELOW) and one AT_TIME per symbol: whichever comes first wakes you, and you re-arm
            then. The previous set on the symbol is replaced in full, an empty array clears it. A condition fires
            once and is removed.
            A condition may carry an action — a standing order the platform carries out by itself the moment the
            condition fires, on any of your positions or pending orders (not necessarily on this symbol):
            MOVE_STOP (positionId, stopLossPrice: tighten that position's stop to this price), CLOSE (positionId,
            closePercent: close that percent of the position at market, 100 = all), CANCEL_ORDER (orderId: cancel
            that pending order). There is no action that opens a position. After MOVE_STOP or CANCEL_ORDER you are
            NOT woken: the next opening message reports what was done, and until then that direction on this
            symbol has no condition armed. After CLOSE you are woken. If the action cannot be carried out (the
            position or order is gone, the stop would loosen or sit on the wrong side of the price) you are woken
            with the reason.""")
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
            watchStore.replace(ctx.traderId(), ctx.roundNo(), symbol, watches, presets, ctx.boundaryTime());
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

    /** 动作填完整了再入轨迹：同一批里两个 get_account 会并发到这儿（别的工具都在 SERIAL_TOOLS 里） */
    private synchronized void record(ObjectNode action) {
        actions.add(action);
    }

    /** 成功：结果同时写进动作轨迹（摘要）与工具返回值（全文）。 */
    private String ok(String tool, ObjectNode args, Object payload) {
        String s = payload instanceof String str ? str : MAPPER.writeValueAsString(payload);
        ObjectNode action = action(tool, args).put("status", "ok");
        // 轨迹里只存摘要，防止 get_account 大 JSON 把决策行撑爆；开仓回执整条存，时间线要读里面逐档的结果
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
        String msg = simReason(e);
        record(action(tool, args).put("status", "error").put("error", msg));
        log.warn("[TradeTools] {} 失败 simUserId={} msg={}", tool, simUserId, msg);
        return "ERROR: " + msg;
    }

    /** sim 那侧的拒因成文 */
    private String simReason(Exception e) {
        // sim 拒因按码查词表跟 ctx.lang()：这句既进模型上下文又进公开时间线，
        // 跟不上语言的话英文 trader 会读到一句中文（模型立刻跟着混）
        String msg = SimTradeClient.describe(e, messages, ctx.lang());
        // 上游异常可能拖着整段响应体（曾见全市场premiumIndex数组进拒因），截断防烧token防撑爆轨迹
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }
}
