package com.mawai.wiibagent.trader.trade;

/** 盯盘条件变了（布 / 整组替换 / 重置作废 / 清除）：盯盘哨兵据此重载名单 */
public record WatchesChanged(long traderId) {
}
