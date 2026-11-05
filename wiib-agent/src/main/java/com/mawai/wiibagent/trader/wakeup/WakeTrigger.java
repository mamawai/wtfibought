package com.mawai.wiibagent.trader.wakeup;

/**
 * 非例行唤醒的触发源：波动警报、持仓事件。调度准入和唤醒回路对两者一视同仁，
 * 差别只在决策行 kind 和开场白首行。
 */
public sealed interface WakeTrigger permits AlertTrigger, EventTrigger {

    /** 触发时刻 ms，落成决策行的 wake_time（不是 K 线边界） */
    long triggeredAt();

    /** 决策行 kind（AiTraderDecision.KIND_*） */
    String kind();
}
