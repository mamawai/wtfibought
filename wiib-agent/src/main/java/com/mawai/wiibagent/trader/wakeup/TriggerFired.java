package com.mawai.wiibagent.trader.wakeup;

/**
 * 某个 trader 被一条非例行触发叫到了。四个触发源（波动哨兵、盯盘哨兵、持仓事件监听、权益回落哨兵）认出该叫谁之后只管广播这个，
 * 准入（互斥 / 时段 / 交接）和换线程都在调度器接的那一头。
 */
public record TriggerFired(long traderId, WakeTrigger trigger) {
}
