package com.mawai.wiibservice.agent.research.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 指数移动平均：seed=首 period 的 SMA，之后递归。复制自现有口径（CryptoIndicatorCalculator.ema），不改原类。 */
public final class Ema {

    private Ema() {
    }

    /** 样本不足 period 返回 null。k=2/(period+1)，ema_t = ema_{t-1} + k·(price_t − ema_{t-1})。 */
    public static BigDecimal ema(List<BigDecimal> values, int period) {
        if (values == null || period <= 0 || values.size() < period) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) sum = sum.add(values.get(i));
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), 12, RoundingMode.HALF_UP);
        BigDecimal k = BigDecimal.valueOf(2.0 / (period + 1));
        for (int i = period; i < values.size(); i++) {
            ema = values.get(i).subtract(ema).multiply(k).add(ema);
        }
        return ema;
    }
}
