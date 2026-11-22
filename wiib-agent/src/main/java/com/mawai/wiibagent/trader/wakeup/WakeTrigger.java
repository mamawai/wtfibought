package com.mawai.wiibagent.trader.wakeup;

/**
 * 非例行唤醒的触发源：波动警报、持仓事件、盯盘条件命中、权益回落警报。归某一个币的（{@link #symbol}），
 * 叫醒的是只处理那个币的单币轮；不归某一个币的按整盘处理。
 */
public sealed interface WakeTrigger permits AlertTrigger, EventTrigger, WatchTrigger, EquityDropTrigger {

    /** 触发时刻 ms，写进开场白；决策行的 wake_time 是真正醒来的时刻 */
    long triggeredAt();

    /** 决策行 kind（AiTraderDecision.KIND_*） */
    String kind();

    /** 这条触发归哪个币；不属于某一个币的（全仓爆仓、权益回落）为 null，按整盘处理 */
    String symbol();
}
