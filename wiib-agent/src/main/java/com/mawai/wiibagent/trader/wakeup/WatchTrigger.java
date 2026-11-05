package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderWatch;

import java.math.BigDecimal;

/** 盯盘条件命中：模型自己布的条件到了。price 是命中时的价（触及 / 到时刻用标记价，收盘类用那根的收盘价） */
public record WatchTrigger(AiTraderWatch watch, BigDecimal price, long firedAt) implements WakeTrigger {

    @Override
    public long triggeredAt() {
        return firedAt;
    }

    @Override
    public String kind() {
        return AiTraderDecision.KIND_WATCH;
    }
}
