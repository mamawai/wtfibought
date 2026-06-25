package com.mawai.wiibquant.agent.behavior;

import com.mawai.wiibquant.agent.SimInternalClient;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.function.Consumer;

/**
 * 用户行为分析 AI 工具（quant 侧）。
 * <p>12 个 @Tool 签名/描述不变，AI agent 行为零变化；数据从直连 sim 库改为 HTTP 调 sim 的
 * {@code /internal/behavior} 端点（{@link SimInternalClient}），quant 与 sim 编译解耦。
 */
@RequiredArgsConstructor
public class BehaviorAnalysisTools {

    private final SimInternalClient simClient;
    private final Consumer<String> onProgress;

    private String fetch(String step, long userId, String endpoint) {
        if (onProgress != null) onProgress.accept(step);
        return simClient.getJson("/internal/behavior/" + userId + "/" + endpoint);
    }

    @Tool(description = "获取用户基础信息：余额、冻结余额、破产次数、注册时间")
    public String getUserProfile(@ToolParam(description = "用户ID") Long userId) {
        return fetch("查询用户基础信息", userId, "user-profile");
    }

    @Tool(description = "获取用户实时资产概览：总资产、持仓市值、待结算、杠杆负债、盈亏等（精确计算，含实时价格）")
    public String getPortfolioSummary(@ToolParam(description = "用户ID") Long userId) {
        return fetch("计算实时资产概览", userId, "portfolio-summary");
    }

    @Tool(description = "获取用户近30日资产快照，含各品类盈亏趋势")
    public String getAssetSnapshots(@ToolParam(description = "用户ID") Long userId) {
        return fetch("获取近30日资产快照", userId, "asset-snapshots");
    }

    @Tool(description = "获取用户股票持仓列表")
    public String getStockPositions(@ToolParam(description = "用户ID") Long userId) {
        return fetch("查询股票持仓", userId, "stock-positions");
    }

    @Tool(description = "获取用户股票交易统计：买入总额、订单数、买卖偏好、订单类型偏好")
    public String getStockTradeStats(@ToolParam(description = "用户ID") Long userId) {
        return fetch("统计股票交易数据", userId, "stock-stats");
    }

    @Tool(description = "获取用户加密货币交易统计：买入总额、卖出总额、持仓数、杠杆使用情况")
    public String getCryptoTradeStats(@ToolParam(description = "用户ID") Long userId) {
        return fetch("统计加密货币交易数据", userId, "crypto-stats");
    }

    @Tool(description = "获取用户合约交易统计：已实现盈亏、订单数、多空偏好、平均杠杆、止损率、爆仓次数")
    public String getFuturesTradeStats(@ToolParam(description = "用户ID") Long userId) {
        return fetch("统计合约交易数据", userId, "futures-stats");
    }

    @Tool(description = "获取用户期权交易统计：买入总额(BTO)、卖出总额(STC)")
    public String getOptionTradeStats(@ToolParam(description = "用户ID") Long userId) {
        return fetch("统计期权交易数据", userId, "option-stats");
    }

    @Tool(description = "获取用户Prediction统计：参与次数、净盈亏、胜率、方向偏好")
    public String getPredictionStats(@ToolParam(description = "用户ID") Long userId) {
        return fetch("统计预测交易数据", userId, "prediction-stats");
    }

    @Tool(description = "获取用户Blackjack统计：总局数、净赢、净输、最大赢、当日已转出积分")
    public String getBlackjackStats(@ToolParam(description = "用户ID") Long userId) {
        return fetch("获取 Blackjack 游戏数据", userId, "blackjack-stats");
    }

    @Tool(description = "获取用户Mines统计：参与次数、净盈亏")
    public String getMinesStats(@ToolParam(description = "用户ID") Long userId) {
        return fetch("获取 Mines 游戏数据", userId, "mines-stats");
    }

    @Tool(description = "获取用户Video Poker统计：参与次数、净盈亏")
    public String getVideoPokerStats(@ToolParam(description = "用户ID") Long userId) {
        return fetch("获取 Video Poker 游戏数据", userId, "videopoker-stats");
    }
}
