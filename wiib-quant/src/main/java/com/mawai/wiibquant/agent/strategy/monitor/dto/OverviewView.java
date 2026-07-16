package com.mawai.wiibquant.agent.strategy.monitor.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 监测看板·实时总览：账户 + 持仓 + 挂单。全部来自 testnet 当下快照（getAccount/positionRisk/openOrders）。
 */
public record OverviewView(
        AccountView account,
        List<PositionView> positions,
        List<OpenOrderView> openOrders,
        // 当前策略执行目标（sim|testnet）：target=sim 时本看板是停更的历史轨，前端据此挂横幅，
        // 免得拿 testnet 旧交易去对"策略账户"页的 sim 记录（两轨本就不该一致）
        String executionTarget) {

    /** 账户资金快照（GET /fapi/v3/account）。 */
    public record AccountView(
            BigDecimal walletBalance,       // 钱包余额
            BigDecimal marginBalance,       // 保证金余额（钱包+未实现）
            BigDecimal unrealizedProfit,    // 持仓未实现盈亏
            BigDecimal availableBalance) {} // 可用余额

    /** 单个持仓（GET /fapi/v3/positionRisk，已过滤 0 仓）。 */
    public record PositionView(
            String symbol,
            String side,                    // LONG / SHORT（由 positionAmt 符号判定）
            BigDecimal positionAmt,         // 持仓数量（绝对值）
            BigDecimal entryPrice,
            BigDecimal markPrice,
            BigDecimal unrealizedProfit,
            BigDecimal liquidationPrice) {}

    /** 单个挂单（GET /fapi/v1/openOrders）。 */
    public record OpenOrderView(
            String symbol,
            Long orderId,
            String clientOrderId,
            String side,
            String type,                    // LIMIT / STOP_MARKET / TAKE_PROFIT_MARKET
            BigDecimal price,
            BigDecimal stopPrice,
            BigDecimal origQty,
            String status,
            Long time) {}
}
