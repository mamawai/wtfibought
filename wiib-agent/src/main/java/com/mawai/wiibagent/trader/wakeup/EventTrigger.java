package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.market.TradeEvent;

/** 持仓事件触发：sim 发来的止损 / 止盈 / 强平 / 全仓爆仓 / 限价成交 */
public record EventTrigger(TradeEvent event) implements WakeTrigger {

    @Override
    public long triggeredAt() {
        return event.at();
    }

    @Override
    public String kind() {
        return AiTraderDecision.KIND_EVENT;
    }
}
