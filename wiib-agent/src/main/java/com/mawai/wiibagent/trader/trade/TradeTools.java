package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderTrail;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibquant.external.sim.SimOrderRetry;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
            "get_account", "open_position", "close_position", "set_stop_loss",
            "set_take_profit", "cancel_order", "write_plan", "set_watch");

    /** 改账户状态的工具名：同一批 tool_call 里有它们就整批串行，不和别的调用交错（ReactLoop.serialTools） */
    public static final Set<String> SERIAL_TOOLS = Set.of(
            "open_position", "close_position", "set_stop_loss", "set_take_profit", "write_plan", "cancel_order", "set_watch");

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
    /** 跟踪止损的规则 */
    private final TraderTrailStore trailStore;
    private final WakeCtx ctx;
    /** 拒因文案：回给模型、也进公开时间线，跟 {@code ctx.lang()} 走 */
    private final PromptCatalog prompts;
    /** sim 那侧的拒因按错误码在这儿成文（余额不足/止损价非法等），同样跟 {@code ctx.lang()} */
    private final MessageCatalog messages;
    /** 开仓那一刻的上下文快照，随计划落库 */
    private final EntryContext entryContext;
    /** 本次唤醒的动作轨迹，唤醒回路收走序列化进 ai_trader_decision.actions_json */
    private final List<ObjectNode> actions = new ArrayList<>();

    public TradeTools(SimTradeClient simTradeClient, long simUserId, Set<String> symbolWhitelist, Set<String> watchSymbols,
                      Function<List<FuturesPositionDTO>, BigDecimal> equityOf, Function<String, BigDecimal> markPrice,
                      TraderPlanStore planStore, TraderWatchStore watchStore, TraderTrailStore trailStore, WakeCtx ctx,
                      PromptCatalog prompts, MessageCatalog messages, EntryContext entryContext) {
        this.simTradeClient = simTradeClient;
        this.simUserId = simUserId;
        this.symbolWhitelist = symbolWhitelist;
        this.watchSymbols = watchSymbols;
        this.equityOf = equityOf;
        this.markPrice = markPrice;
        this.planStore = planStore;
        this.watchStore = watchStore;
        this.trailStore = trailStore;
        this.ctx = ctx;
        this.prompts = prompts;
        this.messages = messages;
        this.entryContext = entryContext;
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
            this; use it only to confirm the account after your own trades within this round.""")
    public String getAccount() {
        try {
            ObjectNode out = MAPPER.createObjectNode();
            Map<String, Object> balance = simTradeClient.getBalanceDetail(simUserId);
            List<FuturesPositionDTO> positions = simTradeClient.getAllPositions(simUserId);
            // 权益与开场白同一口径；available 是 sim 全仓账本的下单额度，钱包余额里还躺着已占用的保证金，不能当可用
            out.put("equity", equityOf.apply(positions));
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
                ps.add(row);
            }
            out.set("positions", ps);
            List<FuturesOrderResponse> pending = simTradeClient.getPendingOrders(simUserId, null);
            out.set("pendingOrders", MAPPER.valueToTree(pending));
            return ok("get_account", null, MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            return fail("get_account", null, e);
        }
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? null : new BigDecimal(String.valueOf(v));
    }

    @Tool(name = "open_position", description = """
            Open a futures position on your sim account (cross margin). Hard rules (violations are rejected
            with a reason you can fix, and the rejection tells you the exact allowed range): leverage must land
            INSIDE the range your owner configured — it is a range, not a ceiling, so picking too low is
            rejected too; when opening a NEW position the margin (=quantity*price/leverage) must not exceed
            the configured percent-of-equity cap — there is no lower bound (adds are exempt); same-symbol
            leverage must match any position or pending order already on that symbol;
            LIMIT price within 5% of mark and resting on the far side of it (LONG below, SHORT above) — a LIMIT
            that would fill at once is rejected, use MARKET for that; stopLossPrice is REQUIRED and on the correct side.
            takeProfits is OPTIONAL: up to 3 rungs, each a price on the profit side of entry plus the percent of the
            position it closes (percents sum to at most 100; whatever is left has no take-profit order). Omit it
            and the position has no take-profit order at all.
            trailCallbackPct is OPTIONAL and turns the stop into a trailing stop: the platform follows the mark
            price and keeps moving the stop to (most favourable price since trailing began) minus/plus that
            percent, only ever tighter; trailActivationPrice, if given, is the mark price at which trailing begins.
            On a LIMIT order trailing begins once the order fills. stopLossPrice is the stop until then.
            playType is your thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER.
            signalsUsed: one sentence citing the concrete data fields your thesis rests on.
            invalidationCondition: the market condition that would prove your thesis wrong (NOT a PnL
            number); it becomes part of your position's plan.
            Stop, take-profit rungs (= the plan's targets) and invalidation condition are all filed into the plan
            and injected back into your next opening message. The receipt carries a facts block for this order:
            the round-trip fee estimate, the stop distance in % and in ATR14 of your heartbeat interval, the loss
            at the stop as a percentage of equity, and — only when take-profit rungs are set — the gain if every
            rung fills and the reward-to-risk after fees (counting only the part the rungs cover).""")
    public String openPosition(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                               @ToolParam(description = "LONG or SHORT") String side,
                               @ToolParam(description = "MARKET or LIMIT") String orderType,
                               @ToolParam(description = "Position size in coins, e.g. 0.01") double quantity,
                               @ToolParam(description = "Leverage; must land inside the range your owner configured (see system prompt)") int leverage,
                               @ToolParam(description = "Limit price; required for LIMIT, ignored for MARKET", required = false) Double limitPrice,
                               // 必须是包装类型：primitive 漏传会被绑成 0.0，护栏的 null 检查就成了摆设，
                               // LONG 的方向校验「0 >= 入场价」为假直接放行——裸多单就是这么开出去的
                               @ToolParam(description = "Stop-loss price, REQUIRED") Double stopLossPrice,
                               @ToolParam(description = "Take-profit rungs, optional: up to 3, each {price, percent}; they are recorded as the plan's targets", required = false) List<TargetRung> takeProfits,
                               @ToolParam(description = "Trailing-stop callback in percent, optional, e.g. 1.5", required = false) Double trailCallbackPct,
                               @ToolParam(description = "Mark price at which trailing begins, optional; omit to trail from the start", required = false) Double trailActivationPrice,
                               @ToolParam(description = "Thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER") String playType,
                               @ToolParam(description = "One sentence citing concrete data behind this trade") String signalsUsed,
                               @ToolParam(description = "Market condition that proves this thesis wrong, e.g. '1h close back below 64200 box top'") String invalidationCondition) {
        TradeGuard.OpenReq req = new TradeGuard.OpenReq(symbol, side, orderType,
                BigDecimal.valueOf(quantity), leverage,
                limitPrice == null ? null : BigDecimal.valueOf(limitPrice),
                stopLossPrice == null ? null : BigDecimal.valueOf(stopLossPrice),
                takeProfits == null ? List.of() : takeProfits,
                playType, signalsUsed, invalidationCondition);
        Trail trail = Trail.of(trailCallbackPct, trailActivationPrice);
        ObjectNode argSummary = openArgs(req, trail);
        if (roundExpired()) {
            return expired("open_position", argSummary);
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
        String reject = TradeGuard.validateOpen(req, equity, mark, symbolWhitelist, ctx.risk(), acct.snaps(),
                prompts, ctx.lang());
        if (reject == null && trail != null) {
            reject = TradeGuard.validateTrail(trail.callbackPct(), trail.activationPrice(), prompts, ctx.lang());
        }
        if (reject != null) {
            // rejected() 自己入轨迹，不许再包一层 record——否则拒绝动作双计
            return rejected("open_position", argSummary, reject);
        }
        // 同向已有仓位或开仓挂单：计划走覆盖而不是新立，旧论点进修订历史
        FuturesPositionDTO sameSide = acct.sameSide(req.symbol(), req.side());
        // 加仓带的止盈档是追加到仓位现有档位后面的，sim 一个仓位最多挂这么多档
        int hanging = sameSide == null || sameSide.getTakeProfits() == null ? 0 : sameSide.getTakeProfits().size();
        if (hanging + req.takeProfits().size() > TradeGuard.SIM_SPLIT_LIMIT) {
            return rejected("open_position", argSummary, prompts.get(ctx.lang(), "trader.reject.targetRungsOnAdd",
                    Map.of("hanging", hanging, "limit", TradeGuard.SIM_SPLIT_LIMIT)));
        }
        boolean coversPlan = sameSide != null || acct.pendingOpen(req.symbol(), req.side());
        // 入场参考价：限价单按限价、市价单按标记价；计划的入场价与回执里的事实都按它算
        BigDecimal entryRef = "LIMIT".equals(req.orderType()) ? req.limitPrice() : mark;
        // 这笔的事实（facts）算术部分在下单前算好；ATR 成交后再取，行情调用不挡下单、取不到也不影响回执
        ObjectNode facts = OpenFacts.of(req, entryRef, equity);
        try {
            FuturesOpenRequest openReq = new FuturesOpenRequest();
            openReq.setSymbol(req.symbol());
            openReq.setSide(req.side());
            // 全仓显式声明：权益口径（全仓保证金不重复计）依赖这个事实，不许靠 sim 远端默认值
            openReq.setMarginMode(FuturesPosition.CROSS);
            openReq.setOrderType(req.orderType());
            openReq.setQuantity(req.quantity());
            openReq.setLeverage(req.leverage());
            openReq.setLimitPrice("LIMIT".equals(req.orderType()) ? req.limitPrice() : null);
            openReq.setMemo("ai_trader:" + req.playType());
            // 幂等键：超时重发认这个键，sim 那边只会成交一次
            openReq.setClientRequestId(UUID.randomUUID().toString());
            FuturesOpenRequest.StopLoss sl = new FuturesOpenRequest.StopLoss();
            sl.setPrice(req.stopLossPrice());
            sl.setQuantity(req.quantity());
            openReq.setStopLosses(List.of(sl));
            List<BigDecimal> rungQty = TargetRung.quantities(req.takeProfits(), req.quantity(), req.symbol());
            List<FuturesOpenRequest.TakeProfit> tps = new ArrayList<>();
            for (int i = 0; i < rungQty.size(); i++) {
                FuturesOpenRequest.TakeProfit tp = new FuturesOpenRequest.TakeProfit();
                tp.setPrice(req.takeProfits().get(i).price());
                tp.setQuantity(rungQty.get(i));
                tps.add(tp);
            }
            openReq.setTakeProfits(tps);
            FuturesOrderResponse resp = SimOrderRetry.send(() -> simTradeClient.openPosition(simUserId, openReq));
            // 市价单 / 市价加仓响应带仓位 id；限价加仓先认它将并入的那个仓；限价首开为 null，成交后才有
            Long positionId = resp.getPositionId() != null ? resp.getPositionId()
                    : sameSide == null ? null : sameSide.getId();
            persistPlan(req, entryRef, coversPlan, positionId);
            OpenFacts.addAtr(facts, req, entryRef, ctx.interval(),
                    entryContext.atr14(req.symbol(), ctx.interval(), System.currentTimeMillis()));
            // 回执＝sim 的订单响应 + facts
            ObjectNode receipt = MAPPER.valueToTree(resp);
            receipt.set("facts", facts);
            if (trail != null && startTrailOnOpen(req, positionId, trail)) {
                receipt.set("trailingStop", trail.json());
            }
            return ok("open_position", argSummary, receipt);
        } catch (SimOrderRetry.UnknownOutcome e) {
            return unknown("open_position", argSummary, e);
        } catch (Exception e) {
            return fail("open_position", argSummary, e);
        }
    }

    /**
     * 成交/挂单即落计划（下轮唤醒回注）：同键已有持仓或挂单走覆盖，否则新立。
     * 写失败只记日志不回错——交易已真实发生，回错误会诱导模型重复开仓。
     */
    private void persistPlan(TradeGuard.OpenReq req, BigDecimal entryRef, boolean coversPlan, Long positionId) {
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
            if (coversPlan) {
                planStore.cover(plan, ctx.lang());
            } else {
                planStore.open(plan);
            }
            // 入场上下文要出网取，异步抓完按列补写到这条计划（覆盖时 id 是旧条目的）
            long nowMs = System.currentTimeMillis();
            Thread.startVirtualThread(() -> captureEntryContext(plan.getId(), req, entryRef, nowMs));
        } catch (Exception e) {
            log.warn("[TradeTools] 计划落库失败 traderId={} {} msg={}", ctx.traderId(), req.symbol(), e.getMessage());
        }
    }

    /**
     * 开仓带的跟踪止损规则落库，往计划记一条开启。单子已经成交，落库失败只记日志、回执里不带 trailingStop，
     * 下一轮账户块里也看不到在跟随。
     */
    private boolean startTrailOnOpen(TradeGuard.OpenReq req, Long positionId, Trail trail) {
        try {
            trailStore.start(ctx.traderId(), ctx.roundNo(), req.symbol(), req.side(), positionId,
                    trail.callbackPct(), trail.activationPrice(), req.stopLossPrice());
            revisePlan(req.symbol(), req.side(), prompts.get(ctx.lang(), "trader.revise.trailStart"),
                    trail.text(prompts, ctx.lang()), prompts.get(ctx.lang(), "trader.revise.trailStartAtOpen"));
            return true;
        } catch (Exception e) {
            log.warn("[TradeTools] 跟踪止损落库失败 traderId={} {} msg={}", ctx.traderId(), req.symbol(), e.getMessage());
            return false;
        }
    }

    /** 跟踪止损的两个参数；两个都没传为 null */
    private record Trail(BigDecimal callbackPct, BigDecimal activationPrice) {
        static Trail of(Double callbackPct, Double activationPrice) {
            return callbackPct == null && activationPrice == null ? null : new Trail(
                    callbackPct == null ? null : BigDecimal.valueOf(callbackPct),
                    activationPrice == null ? null : BigDecimal.valueOf(activationPrice));
        }

        ObjectNode json() {
            return MAPPER.createObjectNode().put("callbackPct", callbackPct).put("activationPrice", activationPrice);
        }

        /** 修订史里的写法：回调幅度，带启动价的再加一句 */
        String text(PromptCatalog prompts, AgentLang lang) {
            String callback = callbackPct.stripTrailingZeros().toPlainString();
            return activationPrice == null
                    ? prompts.get(lang, "trader.revise.trailStartChange", Map.of("callback", callback))
                    : prompts.get(lang, "trader.revise.trailStartChangeFrom", Map.of(
                            "callback", callback, "activation", activationPrice.stripTrailingZeros().toPlainString()));
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
            reason: one sentence on why you are closing now.""")
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

    @Tool(name = "set_stop_loss", description = """
            Change the stop of an open position (positionId from the [Account] block in your opening message, or get_account).
            The stop always covers the WHOLE position — you do not pass a quantity. Two things can be set, at least one:
            stopLossPrice puts the stop at a fixed price. Guard: tighten only — LONG stops may only move UP, SHORT stops
            only DOWN (relative to the current stop); it cannot sit on the far side of the current price (it would
            fire at once). To exit, use close_position.
            trailCallbackPct starts (or re-tunes) a trailing stop: from now on the platform follows the mark price and
            keeps moving the stop to (most favourable price since trailing began) minus/plus that percent, only ever
            tighter, without waking you; trailActivationPrice, if given, is the mark price at which trailing begins.
            Passing stopLossPrice alone on a position that is trailing ends the trailing and leaves the fixed stop.
            reason is REQUIRED and becomes part of the position's public plan revision history.""")
    public String setStopLoss(@ToolParam(description = "Position id from the [Account] block in your opening message (or get_account)") long positionId,
                              @ToolParam(description = "New fixed stop-loss price, optional when trailCallbackPct is given", required = false) Double stopLossPrice,
                              @ToolParam(description = "Trailing-stop callback in percent, optional, e.g. 1.5", required = false) Double trailCallbackPct,
                              @ToolParam(description = "Mark price at which trailing begins, optional; omit to trail from now", required = false) Double trailActivationPrice,
                              @ToolParam(description = "Why you change the stop now, e.g. 'price +2R, lock breakeven'") String reason) {
        Trail trail = Trail.of(trailCallbackPct, trailActivationPrice);
        ObjectNode args = MAPPER.createObjectNode()
                .put("positionId", positionId)
                .put("stopLossPrice", stopLossPrice)
                .put("reason", reason);
        if (trail != null) {
            args.set("trailingStop", trail.json());
        }
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
            if (stopLossPrice == null && trail == null) {
                return rejected("set_stop_loss", args, prompts.get(ctx.lang(), "trader.reject.stopOrTrailRequired"));
            }
            if (trail != null) {
                String invalid = TradeGuard.validateTrail(trail.callbackPct(), trail.activationPrice(), prompts, ctx.lang());
                if (invalid != null) {
                    return rejected("set_stop_loss", args, invalid);
                }
            }
            boolean isLong = "LONG".equals(pos.getSide());
            BigDecimal stopNow = StopOrders.tightest(pos);
            if (stopLossPrice != null) {
                BigDecimal newStop = BigDecimal.valueOf(stopLossPrice);
                BigDecimal mark = markPrice.apply(pos.getSymbol());
                switch (StopOrders.move(simTradeClient, simUserId, pos, newStop, mark)) {
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
                    case StopOrders.Moved m -> revisePlan(pos.getSymbol(), pos.getSide(), prompts.get(ctx.lang(), "trader.revise.moveStop"),
                            (m.from() == null ? prompts.get(ctx.lang(), "trader.revise.none")
                                    : m.from().stripTrailingZeros().toPlainString())
                                    + "→" + newStop.stripTrailingZeros().toPlainString(), reason);
                }
                stopNow = newStop;
            }
            if (trail != null) {
                trailStore.start(ctx.traderId(), ctx.roundNo(), pos.getSymbol(), pos.getSide(), pos.getId(),
                        trail.callbackPct(), trail.activationPrice(), stopNow);
                revisePlan(pos.getSymbol(), pos.getSide(), prompts.get(ctx.lang(), "trader.revise.trailStart"),
                        trail.text(prompts, ctx.lang()), reason);
            } else {
                // 只给了固定价：这个仓位在跟随的话，跟随到此结束
                AiTraderTrail trailing = trailStore.byKey(ctx.traderId(), ctx.roundNo())
                        .get(TraderPlanStore.key(pos.getSymbol(), pos.getSide()));
                if (trailing != null) {
                    trailStore.end(trailing, ctx.lang(), reason);
                }
            }
            return ok("set_stop_loss", args, "{\"ok\":true}");
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
            reason is REQUIRED (public plan revision history).""")
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
            // 占比按仓位当前数量折成数量；sim 是整组替换，空表就是清掉
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
                    targetsText(TargetRung.of(pos)) + "→" + targetsText(rungs), reason);
            return ok("set_take_profit", args, "{\"ok\":true}");
        } catch (Exception e) {
            return fail("set_take_profit", args, e);
        }
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
            planStore.open(plan);
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
                    out.add(new TradeGuard.PosSnap(p.getSymbol(), p.getSide(), p.getLeverage(), true)));
            for (FuturesOrderResponse o : pending) {
                // orderSide 形如 OPEN_LONG / CLOSE_LONG：只有开仓挂单才占坑，平仓挂单是在减仓
                String s = o.getOrderSide();
                if (s == null || !s.startsWith("OPEN_")) {
                    continue;
                }
                out.add(new TradeGuard.PosSnap(o.getSymbol(), s.substring("OPEN_".length()), o.getLeverage(), false));
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
     * 移止损/移止盈/平仓/跟踪止损开启的留痕：按币向找存活计划，这是唤醒中唯一可靠的找法
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

    @Tool(name = "cancel_order", description = "Cancel a pending limit order by orderId (from the [Account] block's pendingOrders in your opening message, or get_account).")
    public String cancelOrder(@ToolParam(description = "Order id from the [Account] block's pendingOrders in your opening message (or get_account)") long orderId) {
        ObjectNode args = MAPPER.createObjectNode().put("orderId", orderId);
        if (roundExpired()) {
            return expired("cancel_order", args);
        }
        try {
            FuturesOrderResponse resp = simTradeClient.cancelOrder(simUserId, orderId);
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
            List<String> actions = new ArrayList<>();
            Account acct = watches.stream().anyMatch(s -> s.action() != null) ? account() : null;
            for (WatchSpec s : watches) {
                if (s.action() == null) {
                    actions.add(null);
                    continue;
                }
                ObjectNode action = MAPPER.valueToTree(s.action());
                reason = bindAction(s.action(), acct, action);
                if (reason != null) {
                    return rejected("set_watch", args, reason);
                }
                actions.add(MAPPER.writeValueAsString(action));
            }
            watchStore.replace(ctx.traderId(), ctx.roundNo(), symbol, watches, actions, ctx.boundaryTime());
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

    private static ObjectNode openArgs(TradeGuard.OpenReq req, Trail trail) {
        ObjectNode args = MAPPER.createObjectNode()
                .put("symbol", req.symbol())
                .put("side", req.side())
                .put("orderType", req.orderType())
                .put("quantity", req.quantity())
                .put("leverage", req.leverage())
                .put("limitPrice", req.limitPrice())
                .put("stopLossPrice", req.stopLossPrice());
        args.set("takeProfits", MAPPER.valueToTree(req.takeProfits()));
        if (trail != null) {
            args.set("trailingStop", trail.json());
        }
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
        // 轨迹里只存摘要，防止 get_account 大 JSON 把决策行撑爆；开仓回执连 facts 约 600 字，整条存
        if (s.length() > 1000) {
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
        // sim 拒因按码查词表跟 ctx.lang()：这句既进模型上下文又进公开时间线，
        // 跟不上语言的话英文 trader 会读到一句中文（模型立刻跟着混）
        String msg = SimTradeClient.describe(e, messages, ctx.lang());
        // 上游异常可能拖着整段响应体（曾见全市场premiumIndex数组进拒因），截断防烧token防撑爆轨迹
        if (msg.length() > 300) {
            msg = msg.substring(0, 300) + "…";
        }
        record(action(tool, args).put("status", "error").put("error", msg));
        log.warn("[TradeTools] {} 失败 simUserId={} msg={}", tool, simUserId, msg);
        return "ERROR: " + msg;
    }
}
