package com.mawai.wiibcommon.market;

import java.math.BigDecimal;

/**
 * sim → agent 的持仓事件（Redis Pub/Sub，频道 {@link #CHANNEL}）：止损 / 止盈 / 强平 / 全仓爆仓 / 限价开仓成交。
 * 载荷就是本记录的 JSON。sim 在落库事务提交后发，agent 收到后把对应 trader 叫醒。
 * Pub/Sub 不存消息：agent 不在线时丢掉的事件，由它下一次唤醒开头的对账兜底。
 *
 * @param userId   sim 用户 id，trader 的子账户就是 ai_trader.sim_user_id
 * @param quantity 这一次平掉 / 成交的量；全仓爆仓时是爆掉的仓位数
 * @param pnl      平仓盈亏（手续费前），成交事件为空
 * @param at       发生时刻 ms
 */
public record TradeEvent(String type, long userId, Long positionId, Long orderId, String symbol, String side,
                         BigDecimal quantity, BigDecimal price, BigDecimal pnl, long at) {

    public static final String CHANNEL = "sim:trade-event";

    public static final String STOP_LOSS = "STOP_LOSS";
    public static final String TAKE_PROFIT = "TAKE_PROFIT";
    public static final String LIQUIDATION = "LIQUIDATION";
    public static final String CROSS_LIQUIDATION = "CROSS_LIQUIDATION";
    public static final String LIMIT_FILLED = "LIMIT_FILLED";
}
