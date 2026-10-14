package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.trader.TraderChatService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 汇总者手里的 trader 只读工具集：纯查库，一个字都不写。动手的三个在 {@link TraderActionToolkit}。
 * <p>
 * <b>userId 是建叶子时烤死的</b>，不是工具参数——工具方法体拿不到用户身份，
 * 做成参数则等于让模型自己填要看谁的
 * trader，模型填错或被诱导就是跨用户越权。叶子按含 userId 的配置指纹缓存，见
 * {@link ChatModelFactory#fingerprint}。
 */
public class TraderQueryToolkit {

    private final TraderChatService traderChatService;
    private final long userId;
    /** 返回 JSON 里的说明字段按它取词表；与叶子同语言（建叶子时烤入，见缓存键） */
    private final AgentLang lang;

    public TraderQueryToolkit(TraderChatService traderChatService, long userId, AgentLang lang) {
        this.traderChatService = traderChatService;
        this.userId = userId;
        this.lang = lang;
    }

    // description 只是编译期兜底：真正发给模型的那份按语言取自词表 tool.<工具名>（见 LocalizedToolCallbacks）

    @Tool(name = "trader_overview", description = """
            Get the current user's AI trader profile: status (RUNNING/PAUSED/LIQUIDATED and why),
            equity vs initial balance, round number, traded symbols, wake interval, risk spec,
            last / next wake-up time and why it cannot be woken right now (null when it can),
            and the FULL review memory notes (what it has learned so far).
            Returns hasTrader=false when the user has not created a trader yet.""")
    public String traderOverview() {
        return traderChatService.overview(userId, lang);
    }

    @Tool(name = "trader_positions", description = """
            Get the current open positions of the user's AI trader: symbol, side, quantity,
            entry price, leverage, unrealized PnL and the currently active stop-loss/take-profit orders.
            Returns hasTrader=false when the user has not created a trader yet.""")
    public String traderPositions() {
        return traderChatService.positions(userId, lang);
    }

    @Tool(name = "trader_decisions", description = """
            Get the recent decision timeline of the user's AI trader, newest first. Each entry has
            kind (TRADE=scheduled wake / ALERT=volatility wake / MANUAL=owner's manual wake / REVIEW=daily retrospective),
            status, equity, the tools it called, and its FULL reasoning text. Can be narrowed to one kind.
            Use this to answer "why did it do that trade" - the reasoning text is the answer.""")
    public String traderDecisions(
            @ToolParam(required = false, description = "How many decisions to return, 1-20, default 5") Integer limit,
            @ToolParam(required = false, description = "Only this kind: TRADE, ALERT, MANUAL or REVIEW. Omit for all kinds")
            String kind) {
        return traderChatService.decisions(userId, limit, kind, lang);
    }

    @Tool(name = "trader_plans", description = """
            Get the trading plans of the user's AI trader: all live plans plus the recently closed ones.
            Each plan carries the thesis (playType), the data it cited (signalsUsed), the invalidation
            condition, entry/stop/target prices and the revision history of stop-loss moves.""")
    public String traderPlans() {
        return traderChatService.plans(userId, lang);
    }

    @Tool(name = "trader_trades", description = """
            Get the closed trades of the user's AI trader, newest first: symbol, side, leverage, entry and
            close price, realized PnL, open/close time, how it was closed (closeManner: stopLoss / takeProfit /
            manual / liquidated), the plan behind it (playType, signalsUsed, invalidation condition, original
            stop and target) and, for a manual close, the close reason it wrote. Also count, wins and total
            realized PnL over the returned trades. Returns hasTrader=false when the user has not created a trader yet.""")
    public String traderTrades(
            @ToolParam(required = false, description = "How many trades to return, 1-50, default 10") Integer limit) {
        return traderChatService.trades(userId, limit, lang);
    }
}
