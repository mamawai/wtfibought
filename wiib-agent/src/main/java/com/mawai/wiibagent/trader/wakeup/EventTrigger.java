package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.market.TradeEvent;

/** 持仓事件触发：sim 发来的止损 / 强平 / 全仓爆仓 / 限价单开出新仓；止盈不叫醒，到不了这里 */
public record EventTrigger(TradeEvent event) implements WakeTrigger {

    @Override
    public long triggeredAt() {
        return event.at();
    }

    @Override
    public String kind() {
        return AiTraderDecision.KIND_EVENT;
    }

    /** 全仓爆仓的事件不带币 */
    @Override
    public String symbol() {
        return event.symbol();
    }
}
