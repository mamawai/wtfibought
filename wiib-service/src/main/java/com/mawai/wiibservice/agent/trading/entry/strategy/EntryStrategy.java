package com.mawai.wiibservice.agent.trading.entry.strategy;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyResult;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;

/**
 * 单条入场路径只负责判断自己是否给出候选，不负责下单。
 */
public interface EntryStrategy {

    enum StrategyKind {
        /** 接收外部传入的方向，老策略每轮分别跑 LONG/SHORT。 */
        DIRECTION_RECEIVER,
        /** 策略自己决定方向，候选结果必须自带 side。 */
        DIRECTION_AUTONOMOUS
    }

    default StrategyKind kind() {
        return StrategyKind.DIRECTION_RECEIVER;
    }

    String path();

    EntryStrategyResult build(EntryStrategyContext input);
}
