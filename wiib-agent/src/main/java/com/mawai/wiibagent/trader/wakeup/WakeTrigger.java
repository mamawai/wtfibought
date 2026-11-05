package com.mawai.wiibagent.trader.wakeup;

/**
 * 非例行唤醒的触发源：波动警报、持仓事件、盯盘条件命中。调度准入和唤醒回路对三者一视同仁，
 * 差别只在决策行 kind 和开场白首行。
 */
public sealed interface WakeTrigger permits AlertTrigger, EventTrigger, WatchTrigger {

    /** 触发时刻 ms，写进开场白；决策行的 wake_time 是真正醒来的时刻 */
    long triggeredAt();

    /** 决策行 kind（AiTraderDecision.KIND_*） */
    String kind();
}
