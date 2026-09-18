package com.mawai.wiibquant.strategy.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面向监控页的策略信号状态快照：一句话状态 + 有序指标，让人看见"策略活着、离触发还差多少"。
 * 只给词表 key 和数值，文字在前端 strategy.json 的 strategies.signals 下：
 * 状态查 state.{key}，指标查 metric.{key}.label / metric.{key}.value。
 * 用途只是展示，不是决策数据——只读 view，不许有副作用。
 */
public record StrategySignalState(String strategyId, String symbol, Text state, List<Text> metrics) {

    /**
     * 词表 key + 占位值。vars 里两个名字有特殊用处（前端 i18next 的规矩）：
     * context 选词条变体（value_{context}），给颜色、方向这类枚举值用；count 选单复数（_one/_other）。
     */
    public record Text(String key, Map<String, Object> vars) {

        /** 占位值成对展开：Text.of("fibo.armed", "fib", 0.66) */
        public static Text of(String key, Object... pairs) {
            Map<String, Object> vars = new LinkedHashMap<>();
            for (int i = 0; i + 1 < pairs.length; i += 2) vars.put((String) pairs[i], pairs[i + 1]);
            return new Text(key, vars);
        }
    }
}
