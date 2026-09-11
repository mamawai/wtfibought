package com.mawai.wiibagent.trader.wakeup;

import java.math.BigDecimal;

/**
 * 波动哨兵触发信息：警报唤醒开场白的事实来源（振幅/方向/时刻全部代码注入，模型零考古）。
 * <p>
 * direction 存语言无关的方向码，不是给人看的字：文案由开场白按 trader 主人的语言取
 * {@code trader.wake.direction.*}。
 */
public record AlertTrigger(String symbol, BigDecimal amplitudePct, BigDecimal price,
                           String direction, long triggeredAt) {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";
    /** 首尾同价：说不出方向，只说"在波动" */
    public static final String FLAT = "FLAT";
}
