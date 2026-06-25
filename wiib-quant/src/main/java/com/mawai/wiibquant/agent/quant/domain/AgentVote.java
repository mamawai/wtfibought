package com.mawai.wiibquant.agent.quant.domain;

import java.util.List;

public record AgentVote(
        String agent,            // microstructure / momentum / regime / volatility / news_event
        String horizon,          // H6 / H12 / H24
        Direction direction,     // LONG / SHORT / NO_TRADE
        double score,            // 方向强度 [-1, 1]，正=偏多，负=偏空
        double confidence,       // 置信度 [0, 1]
        int expectedMoveBps,     // 预期波动(基点)
        int volatilityBps,       // 风险估计(基点)
        List<String> reasonCodes,// 原因码，如 ORDERBOOK_BID_DOMINANT
        List<String> riskFlags   // 风险标志，如 BLACK_SWAN_RISK
) {
    public static AgentVote noTrade(String agent, String horizon, String reason) {
        return new AgentVote(agent, horizon, Direction.NO_TRADE, 0, 0, 0, 0,
                List.of(reason), List.of());
    }
}
