package com.mawai.wiibagent.trader.trade;

/** 跟踪止损的规则变了（开启、换参数、绑上仓位、结束），哨兵重载名单 */
public record TrailsChanged(long traderId) {
}
