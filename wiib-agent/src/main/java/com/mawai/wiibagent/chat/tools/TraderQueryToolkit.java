package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.trader.TraderChatService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.function.Function;

/**
 * 主 agent 手里的 trader 只读工具集：纯查库，一个字都不写。动手的三个在 {@link TraderActionToolkit}。
 * <p>
 * <b>userId 是建叶子时烤死的</b>，不是工具参数——工具方法体拿不到用户身份，
 * 做成参数则等于让模型自己填要看谁的
 * trader，模型填错或被诱导就是跨用户越权。叶子按含 userId 的配置指纹缓存，见
 * {@link com.mawai.wiibagent.chat.ChatModelFactory#fingerprint}。
 * <p>
 * 一个人可以有几个 trader：五个查询都能带 trader（名字），查哪个由传进来的 {@link TraderLocator} 定——
 * 对话里按会话规则（{@link SessionTraderLocator}），指令生成器里固定一个。名字只在烤死的 userId 名下找。
 */
public class TraderQueryToolkit {

    /** 五个查询共用的 trader 参数说明：对话和指令生成器两边都成立，不写不传时怎么定 */
    private static final String TRADER_PARAM = "Name of the user's trader, as listed by trader_list (case-insensitive)";

    private final TraderChatService traderChatService;
    private final long userId;
    /** 返回 JSON 里的说明字段按它取词表；与叶子同语言（建叶子时烤入，见缓存键） */
    private final AgentLang lang;
    private final TraderLocator locator;

    public TraderQueryToolkit(TraderChatService traderChatService, long userId, AgentLang lang, TraderLocator locator) {
        this.traderChatService = traderChatService;
        this.userId = userId;
        this.lang = lang;
        this.locator = locator;
    }

    // 本类工具的描述都在词表 tool.<工具名>（见 LocalizedToolCallbacks）

    @Tool(name = "trader_list")
    public String traderList() {
        return traderChatService.list(userId, lang);
    }

    @Tool(name = "trader_overview")
    public String traderOverview(@ToolParam(required = false, description = TRADER_PARAM) String trader,
                                 ToolContext context) {
        return read(trader, context, t -> traderChatService.overview(t, lang));
    }

    @Tool(name = "trader_positions")
    public String traderPositions(@ToolParam(required = false, description = TRADER_PARAM) String trader,
                                  ToolContext context) {
        return read(trader, context, t -> traderChatService.positions(t, lang));
    }

    @Tool(name = "trader_decisions")
    public String traderDecisions(
            @ToolParam(required = false, description = "How many decisions to return, 1-20, default 5") Integer limit,
            @ToolParam(required = false, description = "Only this kind: TRADE, ALERT, MANUAL or REVIEW. Omit for all kinds")
            String kind,
            @ToolParam(required = false, description = TRADER_PARAM) String trader,
            ToolContext context) {
        return read(trader, context, t -> traderChatService.decisions(t, limit, kind, lang));
    }

    @Tool(name = "trader_plans")
    public String traderPlans(@ToolParam(required = false, description = TRADER_PARAM) String trader,
                              ToolContext context) {
        return read(trader, context, t -> traderChatService.plans(t, lang));
    }

    @Tool(name = "trader_trades")
    public String traderTrades(
            @ToolParam(required = false, description = "How many trades to return, 1-50, default 10") Integer limit,
            @ToolParam(required = false, description = TRADER_PARAM) String trader,
            ToolContext context) {
        return read(trader, context, t -> traderChatService.trades(t, limit, lang));
    }

    /** 先定位再查；定不到就把定位那句原样回给模型 */
    private String read(String trader, ToolContext context, Function<AiTrader, String> query) {
        TraderLocator.Located at = locator.locate(trader, context);
        return at.trader() == null ? at.reply() : query.apply(at.trader());
    }
}
