package com.mawai.wiibservice.agent.trading.entry.confluence;

import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;

/**
 * 入场候选的二层共振过滤，只判断候选是否具备足够旁证。
 */
public interface EntryConfluenceGate {

    ConfluenceGateResult evaluate(EntryStrategyContext context);
}
