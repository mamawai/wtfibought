package com.mawai.wiibagent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibquant.external.sim.SimOrderRetry;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 交易工具（非 Spring bean）：每次唤醒 new 一个，绑定该 trader 的 sim 子账户与白名单。
 * 所有调用（含被 TradeGuard 拒绝的）都记入 actions 列表——决策日志的动作轨迹，
 * 拒绝原因原样返回给模型，模型可自行修正重试。
 */
@Slf4j
public class TradeTools {

    /**
     * 唤醒上下文：计划落库与风险护栏所需的 trader 侧信息；deadlineMs＝本轮预算耗尽的墙钟时刻。
     * lang＝这只 trader 主人的语言：拒因既回给模型也公开在竞技场时间线上，两处都得跟它走。
     */
    public record WakeCtx(long traderId, int roundNo, long boundaryTime, long deadlineMs,
                          TraderRiskConfig risk, AgentLang lang) {
    }

    /** 本类自带富记录（结果/拒因）的工具名——轨迹合并时用富记录替换轨迹收集器的轻量占位 */
    public static final Set<String> RECORDED_TOOLS = Set.of(
            "get_account", "open_position", "close_position", "set_stop_loss",
            "set_take_profit", "cancel_order", "write_plan");

    private final SimTradeClient simTradeClient;
    private final long simUserId;
    private final Set<String> symbolWhitelist;
    /** 按现查持仓算权益：同一轮先平后开，护栏要按平完之后的权益算占比 */
    private final Function<List<FuturesPositionDTO>, BigDecimal> equityOf;
    /** 现价查询（symbol → mark price）；由唤醒回路注入，通常取最近K线收盘价 */
    private final Function<String, BigDecimal> markPrice;
    private final TraderPlanStore planStore;
    private final WakeCtx ctx;
    /** 拒因文案：回给模型、也进公开时间线，跟 {@code ctx.lang()} 走 */
    private final PromptCatalog prompts;
    /** sim 那侧的拒因按错误码在这儿成文（余额不足/止损价非法等），同样跟 {@code ctx.lang()} */
    private final MessageCatalog messages;
    /** 本次唤醒的动作轨迹，唤醒回路收走序列化进 ai_trader_decision.actions_json */
    private final List<JSONObject> actions = new ArrayList<>();

    public TradeTools(SimTradeClient simTradeClient, long simUserId, Set<String> symbolWhitelist,
                      Function<List<FuturesPositionDTO>, BigDecimal> equityOf, Function<String, BigDecimal> markPrice,
                      TraderPlanStore planStore, WakeCtx ctx,
                      PromptCatalog prompts, MessageCatalog messages) {
        this.simTradeClient = simTradeClient;
        this.simUserId = simUserId;
        this.symbolWhitelist = symbolWhitelist;
        this.equityOf = equityOf;
        this.markPrice = markPrice;
        this.planStore = planStore;
        this.ctx = ctx;
        this.prompts = prompts;
        this.messages = messages;
    }

    public List<JSONObject> actions() {
        return actions;
    }

    // 描述只客观说"返回什么"，不下行为指令：系统提示词明说账户状态每轮已注入、
    // 让把调用预算花在行情求证上，这里再写 ALWAYS check 就是两条强指令打架
    @Tool(name = "get_account", description = """
            Get your full account state: available balance, open positions (with id, side, quantity,
            entry price, leverage, unrealized PnL, liquidation price, current stop-loss/take-profit)
            and pending limit orders. This same state is already injected into your opening message
            every round (the [Account] block), so you normally do not need to call this; use it only
            to confirm the account after your own trades within this round.""")
    public String getAccount() {
        try {
            JSONObject out = new JSONObject();
            out.put("balance", simTradeClient.getBalance(simUserId));
            List<FuturesPositionDTO> positions = simTradeClient.getAllPositions(simUserId);
            JSONArray ps = new JSONArray();
            for (FuturesPositionDTO p : positions) {
                JSONObject row = new JSONObject();
                row.put("positionId", p.getId());
                row.put("symbol", p.getSymbol());
                row.put("side", p.getSide());
                row.put("quantity", p.getQuantity());
                row.put("entryPrice", p.getEntryPrice());
                row.put("leverage", p.getLeverage());
                row.put("margin", p.getMargin());
                row.put("unrealizedPnl", p.getUnrealizedPnl());
                row.put("liquidationPrice", p.getLiquidationPrice());
                row.put("stopLosses", p.getStopLosses());
                row.put("takeProfits", p.getTakeProfits());
                ps.add(row);
            }
            out.put("positions", ps);
            List<FuturesOrderResponse> pending = simTradeClient.getPendingOrders(simUserId, null);
            out.put("pendingOrders", JSON.toJSON(pending));
            return ok("get_account", null, out.toJSONString());
        } catch (Exception e) {
            return fail("get_account", null, e);
        }
    }

    @Tool(name = "open_position", description = """
            Open a futures position on your sim account (cross margin). Hard rules (violations are rejected
            with a reason you can fix, and the rejection tells you the exact allowed range): leverage must land
            INSIDE the range your owner configured — it is a range, not a ceiling, so picking too low is
            rejected too; when opening a NEW position the margin (=quantity*price/leverage) must land
            inside the configured percent-of-equity band (adds are exempt); same-symbol leverage must
            match any position or pending order already on that symbol;
            LIMIT price within 5% of mark; stopLossPrice and takeProfitPrice are both REQUIRED and on the correct side.
            playType is your thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER.
            signalsUsed: one sentence citing the concrete data fields your thesis rests on.
            invalidationCondition: the market condition that would prove your thesis wrong (NOT a PnL
            number) — it becomes part of your position's plan; while it has not fired, price has not reached
            the target and your owner has not spoken, there is no ground for a manual exit.
            Stop, take-profit (= the plan's target) and invalidation condition are all filed into the plan and
            injected back into your next opening message.""")
    public String openPosition(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                               @ToolParam(description = "LONG or SHORT") String side,
                               @ToolParam(description = "MARKET or LIMIT") String orderType,
                               @ToolParam(description = "Position size in coins, e.g. 0.01") double quantity,
                               @ToolParam(description = "Leverage; must land inside the range your owner configured (see system prompt)") int leverage,
                               @ToolParam(description = "Limit price; required for LIMIT, ignored for MARKET", required = false) Double limitPrice,
                               // 必须是包装类型：primitive 漏传会被绑成 0.0，护栏的 null 检查就成了摆设，
                               // LONG 的方向校验「0 >= 入场价」为假直接放行——裸多单就是这么开出去的
                               @ToolParam(description = "Stop-loss price, REQUIRED") Double stopLossPrice,
                               @ToolParam(description = "Take-profit price, REQUIRED; it is recorded as the plan's target") Double takeProfitPrice,
                               @ToolParam(description = "Thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER") String playType,
                               @ToolParam(description = "One sentence citing concrete data behind this trade") String signalsUsed,
                               @ToolParam(description = "Market condition that proves this thesis wrong, e.g. '1h close back below 64200 box top'") String invalidationCondition) {
        TradeGuard.OpenReq req = new TradeGuard.OpenReq(symbol, side, orderType,
                BigDecimal.valueOf(quantity), leverage,
                limitPrice == null ? null : BigDecimal.valueOf(limitPrice),
                stopLossPrice == null ? null : BigDecimal.valueOf(stopLossPrice),
                takeProfitPrice == null ? null : BigDecimal.valueOf(takeProfitPrice),
                playType, signalsUsed, invalidationCondition);
        JSONObject argSummary = openArgs(req);
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
        if (reject != null) {
            // action() 内部已入轨迹列表，不许再包一层 add——否则拒绝动作双计
            return rejected("open_position", argSummary, reject);
        }
        // 同向已有仓位＝这单是加仓，计划走覆盖而不是新立
        FuturesPositionDTO sameSide = acct.sameSide(req.symbol(), req.side());
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
            if (req.takeProfitPrice() != null) {
                FuturesOpenRequest.TakeProfit tp = new FuturesOpenRequest.TakeProfit();
                tp.setPrice(req.takeProfitPrice());
                tp.setQuantity(req.quantity());
                openReq.setTakeProfits(List.of(tp));
            }
            FuturesOrderResponse resp = SimOrderRetry.send(() -> simTradeClient.openPosition(simUserId, openReq));
            persistPlan(req, mark, sameSide != null, resp);
            return ok("open_position", argSummary, JSON.toJSONString(resp));
        } catch (SimOrderRetry.UnknownOutcome e) {
            return unknown("open_position", argSummary, e);
        } catch (Exception e) {
            return fail("open_position", argSummary, e);
        }
    }

    /** 成交/挂单即落计划（下轮唤醒回注）；写失败只记日志不回错——交易已真实发生，回错误会诱导模型重复开仓。 */
    private void persistPlan(TradeGuard.OpenReq req, BigDecimal mark, boolean isAddOn, FuturesOrderResponse resp) {
        try {
            AiTraderPlan plan = new AiTraderPlan();
            plan.setTraderId(ctx.traderId());
            plan.setRoundNo(ctx.roundNo());
            plan.setSymbol(req.symbol());
            plan.setSide(req.side());
            plan.setPlayType(req.playType());
            plan.setSignalsUsed(req.signalsUsed());
            plan.setInvalidationCondition(req.invalidationCondition());
            plan.setEntryPrice("LIMIT".equals(req.orderType()) ? req.limitPrice() : mark);
            plan.setStopLossPrice(req.stopLossPrice());
            plan.setTakeProfitPrice(req.takeProfitPrice());
            plan.setOpenedWakeTime(ctx.boundaryTime());
            // 市价单/市价加仓响应即带仓位id；限价挂单为null，成交后下次唤醒开头补上（TraderPlanStore.rebind）
            plan.setPositionId(resp.getPositionId());
            planStore.upsert(plan, isAddOn, ctx.lang());
        } catch (Exception e) {
            log.warn("[TradeTools] 计划落库失败 traderId={} {} msg={}", ctx.traderId(), req.symbol(), e.getMessage());
        }
    }

    @Tool(name = "close_position", description = """
            Close (part of) an open position by positionId (from the [Account] block in your opening message, or get_account), at market price.
            quantity: coins to close; pass the full position quantity to close it entirely.
            reason: one sentence on why you are closing now.""")
    public String closePosition(@ToolParam(description = "Position id from the [Account] block in your opening message (or get_account)") long positionId,
                                @ToolParam(description = "Quantity in coins to close") double quantity,
                                @ToolParam(description = "One sentence: why close now") String reason) {
        JSONObject args = new JSONObject()
                .fluentPut("positionId", positionId)
                .fluentPut("quantity", quantity)
                .fluentPut("reason", reason);
        if (roundExpired()) {
            return expired("close_position", args);
        }
        try {
            FuturesCloseRequest req = new FuturesCloseRequest();
            req.setPositionId(positionId);
            req.setQuantity(BigDecimal.valueOf(quantity));
            req.setOrderType("MARKET");
            req.setClientRequestId(UUID.randomUUID().toString());
            FuturesOrderResponse resp = SimOrderRetry.send(() -> simTradeClient.closePosition(simUserId, req));
            reviseClose(positionId, BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString(), reason);
            return ok("close_position", args, JSON.toJSONString(resp));
        } catch (SimOrderRetry.UnknownOutcome e) {
            return unknown("close_position", args, e);
        } catch (Exception e) {
            return fail("close_position", args, e);
        }
    }

    @Tool(name = "set_stop_loss", description = """
            Replace the stop-loss of an open position (positionId from the [Account] block in your opening message, or get_account). The new stop
            always covers the WHOLE position — you do not pass a quantity. TIGHTEN ONLY:
            LONG stops may only move UP, SHORT stops only DOWN (relative to the current stop) —
            widening a stop means your thesis is shaken; check your invalidation condition instead.
            Do NOT slam the stop right next to the current price to force an instant trigger while
            in loss — that is a panic exit in disguise; if the thesis is invalidated, say so and use
            close_position instead. reason is REQUIRED and becomes part of the position's public
            plan revision history.""")
    public String setStopLoss(@ToolParam(description = "Position id from the [Account] block in your opening message (or get_account)") long positionId,
                              @ToolParam(description = "New stop-loss price") double stopLossPrice,
                              @ToolParam(description = "Why you move the stop now, e.g. 'price +2R, lock breakeven'") String reason) {
        JSONObject args = new JSONObject()
                .fluentPut("positionId", positionId)
                .fluentPut("stopLossPrice", stopLossPrice)
                .fluentPut("reason", reason);
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
            // 先校验站在现价哪一侧：止损挂到现价另一侧，下一tick就是市价平仓——比放宽止损更恶劣的
            // 恐慌平仓马甲。这条不能挂在"有基线"的前提下：仓位从没挂过止损时基线为null，
            // 只许收紧整条判定被短路，任意价格都能放行
            BigDecimal mark = markPrice.apply(pos.getSymbol());
            if (isLong ? newStop.compareTo(mark) >= 0 : newStop.compareTo(mark) <= 0) {
                return rejected("set_stop_loss", args, prompts.get(ctx.lang(), "trader.reject.stopWrongSide", Map.of(
                        "mark", mark.stripTrailingZeros().toPlainString(),
                        "rule", prompts.get(ctx.lang(), isLong ? "trader.reject.stopRuleLong" : "trader.reject.stopRuleShort"),
                        "given", newStop.stripTrailingZeros().toPlainString())));
            }
            // 止损只许收紧：放宽止损=放大风险=移动球门柱；想给仓位更多空间说明论点已动摇，该查失效条件而不是松止损
            // 基准取最紧那档：sim 是整组替换，比最紧档松的新价会让原有某档变松，一律拒
            BigDecimal tightest = TradeGuard.extremePrice(
                    pos.getStopLosses() == null ? List.of()
                            : pos.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList(), isLong);
            if (tightest != null && (isLong ? newStop.compareTo(tightest) < 0 : newStop.compareTo(tightest) > 0)) {
                return rejected("set_stop_loss", args, prompts.get(ctx.lang(), "trader.reject.stopOnlyTighter",
                        Map.of("current", tightest.stripTrailingZeros().toPlainString())));
            }
            FuturesStopLossRequest req = new FuturesStopLossRequest();
            req.setPositionId(positionId);
            FuturesStopLossRequest.StopLossItem item = new FuturesStopLossRequest.StopLossItem();
            item.setPrice(newStop);
            // 覆盖量从仓位现取：本版 sl/tp 都是全仓单，让模型报数量它会照抄旧值，
            // 加仓后仓位变大而覆盖量没跟上，一半仓位就裸奔了
            item.setQuantity(pos.getQuantity());
            req.setStopLosses(List.of(item));
            simTradeClient.setStopLoss(simUserId, req);
            revisePlan(pos, prompts.get(ctx.lang(), "trader.revise.moveStop"),
                    (tightest == null ? prompts.get(ctx.lang(), "trader.revise.none")
                            : tightest.stripTrailingZeros().toPlainString())
                            + "→" + newStop.stripTrailingZeros().toPlainString(), reason);
            return ok("set_stop_loss", args, "{\"ok\":true}");
        } catch (Exception e) {
            return fail("set_stop_loss", args, e);
        }
    }

    @Tool(name = "set_take_profit", description = """
            Replace the take-profit of an open position (positionId from the [Account] block in your opening message, or get_account). The new target
            always covers the WHOLE position — you do not pass a quantity. AWAY ONLY:
            LONG targets may only move UP, SHORT targets only DOWN — lowering a LONG target toward
            price would be a disguised panic exit; to leave early, cite your invalidation condition
            and use close_position instead. reason is REQUIRED (public plan revision history).""")
    public String setTakeProfit(@ToolParam(description = "Position id from the [Account] block in your opening message (or get_account)") long positionId,
                                @ToolParam(description = "New take-profit price") double takeProfitPrice,
                                @ToolParam(description = "Why you move the target now, e.g. 'trend accelerating, extend to next resistance'") String reason) {
        JSONObject args = new JSONObject()
                .fluentPut("positionId", positionId)
                .fluentPut("takeProfitPrice", takeProfitPrice)
                .fluentPut("reason", reason);
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
            boolean isLong = "LONG".equals(pos.getSide());
            BigDecimal newTarget = BigDecimal.valueOf(takeProfitPrice);
            // 同 set_stop_loss：目标位站到现价另一侧就是秒触发的"止盈带走"马甲，
            // 且原仓没挂过止盈时基线为null，只许远离那条判定同样管不住
            BigDecimal mark = markPrice.apply(pos.getSymbol());
            if (isLong ? newTarget.compareTo(mark) <= 0 : newTarget.compareTo(mark) >= 0) {
                return rejected("set_take_profit", args, prompts.get(ctx.lang(), "trader.reject.targetWrongSide", Map.of(
                        "mark", mark.stripTrailingZeros().toPlainString(),
                        "rule", prompts.get(ctx.lang(), isLong ? "trader.reject.targetRuleLong" : "trader.reject.targetRuleShort"),
                        "given", newTarget.stripTrailingZeros().toPlainString())));
            }
            // 止盈只许远离入场：把目标降到现价上方一点点秒触发＝"止盈带走"马甲下的恐慌平仓
            BigDecimal farthest = TradeGuard.extremePrice(
                    pos.getTakeProfits() == null ? List.of()
                            : pos.getTakeProfits().stream().map(FuturesTakeProfit::getPrice).toList(), isLong);
            if (farthest != null && (isLong ? newTarget.compareTo(farthest) < 0 : newTarget.compareTo(farthest) > 0)) {
                return rejected("set_take_profit", args, prompts.get(ctx.lang(), "trader.reject.targetOnlyFarther",
                        Map.of("current", farthest.stripTrailingZeros().toPlainString())));
            }
            FuturesTakeProfitRequest req = new FuturesTakeProfitRequest();
            req.setPositionId(positionId);
            FuturesTakeProfitRequest.TakeProfitItem item = new FuturesTakeProfitRequest.TakeProfitItem();
            item.setPrice(newTarget);
            // 同 set_stop_loss：全仓覆盖，量归代码取
            item.setQuantity(pos.getQuantity());
            req.setTakeProfits(List.of(item));
            simTradeClient.setTakeProfit(simUserId, req);
            revisePlan(pos, prompts.get(ctx.lang(), "trader.revise.moveTarget"),
                    (farthest == null ? prompts.get(ctx.lang(), "trader.revise.none")
                            : farthest.stripTrailingZeros().toPlainString())
                            + "→" + newTarget.stripTrailingZeros().toPlainString(), reason);
            return ok("set_take_profit", args, "{\"ok\":true}");
        } catch (Exception e) {
            return fail("set_take_profit", args, e);
        }
    }

    @Tool(name = "write_plan", description = """
            Backfill a trading plan for an open position that has NO plan record (positionId from the
            [Account] block in your opening message, or get_account). The stop is copied from the position's
            current stop-loss order; targetPrice falls back to its current take-profit order when omitted.
            Rejected if the position already has a plan — plans are immutable; the only
            legal ways to change a thesis are adding to the position or closing and reopening.""")
    public String writePlan(@ToolParam(description = "Position id from the [Account] block in your opening message (or get_account)") long positionId,
                            @ToolParam(description = "Thesis label: BREAKOUT/PULLBACK/REVERSAL/TREND_FOLLOW/RANGE/NEWS/FUNDING/OTHER") String playType,
                            @ToolParam(description = "One sentence citing concrete data behind holding this position") String signalsUsed,
                            @ToolParam(description = "Market condition that proves this thesis wrong (NOT a PnL number)") String invalidationCondition,
                            @ToolParam(description = "Target price, optional", required = false) Double targetPrice) {
        JSONObject args = new JSONObject()
                .fluentPut("positionId", positionId)
                .fluentPut("playType", playType)
                .fluentPut("signalsUsed", signalsUsed)
                .fluentPut("invalidationCondition", invalidationCondition)
                .fluentPut("targetPrice", targetPrice);
        try {
            FuturesPositionDTO pos = findPosition(positionId);
            if (pos == null) {
                return rejected("write_plan", args, prompts.get(ctx.lang(), "trader.reject.positionIdNotFound"));
            }
            if (planStore.find(ctx.traderId(), ctx.roundNo(), pos.getSymbol(), pos.getSide()) != null) {
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
            boolean isLong = "LONG".equals(pos.getSide());
            AiTraderPlan plan = new AiTraderPlan();
            plan.setTraderId(ctx.traderId());
            plan.setRoundNo(ctx.roundNo());
            plan.setSymbol(pos.getSymbol());
            plan.setSide(pos.getSide());
            plan.setPlayType(playType);
            plan.setSignalsUsed(signalsUsed);
            plan.setInvalidationCondition(invalidationCondition);
            plan.setEntryPrice(pos.getEntryPrice());
            plan.setStopLossPrice(TradeGuard.extremePrice(pos.getStopLosses() == null ? List.of()
                    : pos.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList(), isLong));
            // 目标位没传就抄仓位现挂的止盈：计划的 target 是"到目标位落袋"和复盘配对的依据，不能空着
            plan.setTakeProfitPrice(targetPrice != null ? BigDecimal.valueOf(targetPrice)
                    : TradeGuard.extremePrice(pos.getTakeProfits() == null ? List.of()
                            : pos.getTakeProfits().stream().map(FuturesTakeProfit::getPrice).toList(), isLong));
            // 补立也盖仓位id：入参已经 findPosition 对 sim 校验过，不是模型凭空抄的
            plan.setPositionId(pos.getId());
            // 持有时长按仓位真实开仓时间算，不是补立时刻——补立不能"清零仓龄"
            plan.setOpenedWakeTime(pos.getCreatedAt() != null
                    ? pos.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : ctx.boundaryTime());
            TraderPlanStore.appendRevision(plan, ctx.boundaryTime(), prompts.get(ctx.lang(), "trader.revise.fileNew"),
                    prompts.get(ctx.lang(), "trader.revise.fileNewNote"), signalsUsed);
            // 前置校验已确认无计划，走 insert 路径；isAddOn=false 语义上也对——补立不是加仓
            planStore.upsert(plan, false, ctx.lang());
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

    /** 有计划就留修订；没有计划（旧仓）不强求——write_plan 是它的补救路径。 */
    private void revisePlan(FuturesPositionDTO pos, String type, String change, String reason) {
        try {
            AiTraderPlan plan = planStore.find(ctx.traderId(), ctx.roundNo(), pos.getSymbol(), pos.getSide());
            if (plan != null) {
                planStore.revise(plan, ctx.boundaryTime(), type, change, reason);
            }
        } catch (Exception e) {
            log.warn("[TradeTools] 修订落库失败 traderId={} {} msg={}", ctx.traderId(), pos.getSymbol(), e.getMessage());
        }
    }

    /** 平仓/减仓留痕：平仓工具手里只有 positionId，按它找计划。交易已成交，留痕失败只记日志不回错——回错会诱导模型再平一次 */
    private void reviseClose(long positionId, String change, String reason) {
        try {
            AiTraderPlan plan = planStore.findLiveByPositionId(ctx.traderId(), ctx.roundNo(), positionId);
            // 旧仓可能没有计划，revisePlan 同款
            if (plan != null) {
                planStore.revise(plan, ctx.boundaryTime(), prompts.get(ctx.lang(), "trader.revise.close"), change, reason);
            }
        } catch (Exception e) {
            log.warn("[TradeTools] 平仓留痕失败 traderId={} positionId={} msg={}", ctx.traderId(), positionId, e.getMessage());
        }
    }

    /** 本轮预算已耗尽：唤醒回路那边正在超时作废，这时候再下单就是给下一轮留没人认领的仓位。 */
    private boolean roundExpired() {
        return System.currentTimeMillis() > ctx.deadlineMs();
    }

    private String expired(String tool, JSONObject args) {
        return rejected(tool, args, prompts.get(ctx.lang(), "trader.reject.expired"));
    }

    /** 结果未知：绝不能当普通失败回——模型看见 ERROR 会重下一单，那就是双仓。 */
    private String unknown(String tool, JSONObject args, SimOrderRetry.UnknownOutcome e) {
        String cause = String.valueOf(e.getCause().getMessage());
        if (cause.length() > 200) {
            cause = cause.substring(0, 200) + "…";
        }
        action(tool, args).fluentPut("status", "unknown").fluentPut("error", cause);
        log.warn("[TradeTools] {} 结果未知 simUserId={} msg={}", tool, simUserId, cause);
        return prompts.get(ctx.lang(), "trader.reject.unknown", Map.of("cause", String.valueOf(cause)));
    }

    /** 护栏拒绝：与 open_position 的 REJECTED 同一语义，进动作轨迹，模型可修正重试。 */
    private String rejected(String tool, JSONObject args, String reason) {
        action(tool, args).fluentPut("rejected", reason);
        return "REJECTED: " + reason;
    }

    @Tool(name = "cancel_order", description = "Cancel a pending limit order by orderId (from the [Account] block's pendingOrders in your opening message, or get_account).")
    public String cancelOrder(@ToolParam(description = "Order id from the [Account] block's pendingOrders in your opening message (or get_account)") long orderId) {
        JSONObject args = new JSONObject().fluentPut("orderId", orderId);
        if (roundExpired()) {
            return expired("cancel_order", args);
        }
        try {
            FuturesOrderResponse resp = simTradeClient.cancelOrder(simUserId, orderId);
            return ok("cancel_order", args, JSON.toJSONString(resp));
        } catch (Exception e) {
            return fail("cancel_order", args, e);
        }
    }

    private static JSONObject openArgs(TradeGuard.OpenReq req) {
        return new JSONObject()
                .fluentPut("symbol", req.symbol())
                .fluentPut("side", req.side())
                .fluentPut("orderType", req.orderType())
                .fluentPut("quantity", req.quantity())
                .fluentPut("leverage", req.leverage())
                .fluentPut("limitPrice", req.limitPrice())
                .fluentPut("stopLossPrice", req.stopLossPrice())
                .fluentPut("takeProfitPrice", req.takeProfitPrice())
                .fluentPut("playType", req.playType())
                .fluentPut("signalsUsed", req.signalsUsed())
                .fluentPut("invalidationCondition", req.invalidationCondition());
    }

    private JSONObject action(String tool, JSONObject args) {
        JSONObject a = new JSONObject().fluentPut("tool", tool);
        if (args != null) {
            a.put("args", args);
        }
        actions.add(a);
        return a;
    }

    /** 成功：结果同时写进动作轨迹（摘要）与工具返回值（全文）。 */
    private String ok(String tool, JSONObject args, Object payload) {
        String s = payload instanceof String str ? str : JSON.toJSONString(payload);
        // 轨迹里只存摘要，防止 get_account 大 JSON 把决策行撑爆
        Object result;
        if (s.length() > 400) {
            result = s.substring(0, 400) + "…";
        } else {
            try {
                result = JSON.parse(s);
            } catch (Exception e) {
                result = s; // 不是 JSON 的结果原样入轨迹
            }
        }
        action(tool, args).fluentPut("status", "ok").fluentPut("result", result);
        return s;
    }

    private String fail(String tool, JSONObject args, Exception e) {
        // sim 拒因按码查词表跟 ctx.lang()：这句既进模型上下文又进公开时间线，
        // 跟不上语言的话英文 trader 会读到一句中文（模型立刻跟着混）
        String msg = SimTradeClient.describe(e, messages, ctx.lang());
        // 上游异常可能拖着整段响应体（曾见全市场premiumIndex数组进拒因），截断防烧token防撑爆轨迹
        if (msg.length() > 300) {
            msg = msg.substring(0, 300) + "…";
        }
        action(tool, args).fluentPut("status", "error").fluentPut("error", msg);
        log.warn("[TradeTools] {} 失败 simUserId={} msg={}", tool, simUserId, msg);
        return "ERROR: " + msg;
    }
}
