package com.mawai.wiibservice.agent.trading.entry.strategy;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyResult;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;

/**
 * 单条入场路径只负责判断自己是否给出候选，不负责下单。
 */
public interface EntryStrategy {

    EntryStrategyResult build(EntryStrategyContext input);
}
