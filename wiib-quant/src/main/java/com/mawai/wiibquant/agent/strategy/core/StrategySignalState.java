package com.mawai.wiibquant.agent.strategy.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面向监控页的策略信号状态快照：一句话状态 + 有序指标表（label→值，前端原样渲染）。
 * 用途是让人看见"策略活着、离触发还差多少"，不是决策数据——只读 view，不许有副作用。
 */
public record StrategySignalState(String strategyId, String symbol, String state,
                                  Map<String, String> metrics) {

    /** kv 成对展开成有序 map：metrics("上轨","123","下轨","98") */
    public static Map<String, String> kv(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }
}
