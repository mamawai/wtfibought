package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.handover.Clustered;

/** 盯盘条件变了（布 / 整组替换 / 重置作废 / 清除）：盯盘哨兵据此重载名单。交接期间由事件桥转到另一个实例 */
@Clustered
public record WatchesChanged(long traderId) {
}
