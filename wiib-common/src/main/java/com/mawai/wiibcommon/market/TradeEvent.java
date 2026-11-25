package com.mawai.wiibcommon.market;

import java.math.BigDecimal;

/**
 * sim → agent 的持仓事件（Redis Stream {@link #STREAM}，字段 json 是本记录的 JSON）：止损 / 止盈 / 强平 / 全仓爆仓 / 限价单开出新仓。
 * sim 在落库事务提交后写，agent 读到后把对应 trader 叫醒（止盈不叫）。
 * Stream 存着消息：agent 换实例的那几秒没人读，消息在消费组里等着，不丢。
 *
 * @param userId   sim 用户 id，trader 的子账户就是 ai_trader.sim_user_id
 * @param quantity 这一次平掉 / 成交的量；全仓爆仓时是爆掉的仓位数
 * @param pnl      平仓净盈亏（已扣手续费，强平再扣清算费）；全仓爆仓为整次结算额；限价成交为空
 * @param at       发生时刻 ms
 */
public record TradeEvent(String type, long userId, Long positionId, Long orderId, String symbol, String side,
                         BigDecimal quantity, BigDecimal price, BigDecimal pnl, long at) {

    public static final String STREAM = "stream:trade-event";

    public static final String STOP_LOSS = "STOP_LOSS";
    public static final String TAKE_PROFIT = "TAKE_PROFIT";
    public static final String LIQUIDATION = "LIQUIDATION";
    public static final String CROSS_LIQUIDATION = "CROSS_LIQUIDATION";
    public static final String LIMIT_FILLED = "LIMIT_FILLED";
}
