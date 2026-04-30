package com.mawai.wiibservice.agent.trading;

/**
 * 单条入场路径只负责判断自己是否给出候选，不负责下单。
 */
interface EntryStrategy {

    EntryStrategyResult build(EntryStrategyContext input);
}
