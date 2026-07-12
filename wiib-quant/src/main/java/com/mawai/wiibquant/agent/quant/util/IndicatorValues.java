package com.mawai.wiibquant.agent.quant.util;

import java.math.BigDecimal;

/** 指标 map 原值解析：JSON 反序列化后数值可能是 BigDecimal/Integer/Double 等 Number 变体。 */
public final class IndicatorValues {

    private IndicatorValues() {
    }

    /** Object → BigDecimal；非数值返回 null，调用方按"缺数据"跳过该信号。 */
    public static BigDecimal toBd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
