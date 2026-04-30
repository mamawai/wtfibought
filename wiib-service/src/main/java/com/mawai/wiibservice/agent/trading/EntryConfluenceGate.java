package com.mawai.wiibservice.agent.trading;

/**
 * 入场候选的二层共振过滤，只判断候选是否具备足够旁证。
 */
interface EntryConfluenceGate {

    ConfluenceGateResult evaluate(EntryStrategyContext context);
}
