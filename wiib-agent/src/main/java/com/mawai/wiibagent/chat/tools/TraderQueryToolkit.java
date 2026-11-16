package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.trader.TraderChatService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 主 agent 手里的 trader 只读工具集：纯查库，一个字都不写。动手的三个在 {@link TraderActionToolkit}。
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

    // 本类工具的描述都在词表 tool.<工具名>（见 LocalizedToolCallbacks）

    @Tool(name = "trader_overview")
    public String traderOverview() {
        return traderChatService.overview(userId, lang);
    }

    @Tool(name = "trader_positions")
    public String traderPositions() {
        return traderChatService.positions(userId, lang);
    }

    @Tool(name = "trader_decisions")
    public String traderDecisions(
            @ToolParam(required = false, description = "How many decisions to return, 1-20, default 5") Integer limit,
            @ToolParam(required = false, description = "Only this kind: TRADE, ALERT, MANUAL or REVIEW. Omit for all kinds")
            String kind) {
        return traderChatService.decisions(userId, limit, kind, lang);
    }

    @Tool(name = "trader_plans")
    public String traderPlans() {
        return traderChatService.plans(userId, lang);
    }

    @Tool(name = "trader_trades")
    public String traderTrades(
            @ToolParam(required = false, description = "How many trades to return, 1-50, default 10") Integer limit) {
        return traderChatService.trades(userId, limit, lang);
    }
}
