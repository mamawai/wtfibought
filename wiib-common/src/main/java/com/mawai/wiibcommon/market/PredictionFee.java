package com.mawai.wiibcommon.market;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Polymarket 5 分钟盘的吃单费（crypto_fees_v2）：fee = 份数 × 0.07 × p × (1 − p)，p 是成交价。
 * p=0.5 时最贵（每份 1.75¢），往两头递减；只收吃单方，我们买卖都按盘口最优价成交，全按吃单算。
 * sim 买入卖出都从这里取，改费率只改这里。
 */
public final class PredictionFee {

    public static final BigDecimal RATE = new BigDecimal("0.07");
    /** 与注单 cost/payout 同刻度 */
    private static final int SCALE = 4;

    private PredictionFee() {
    }

    /** 每份的费（USDT） */
    public static BigDecimal perShare(BigDecimal price) {
        return RATE.multiply(price).multiply(BigDecimal.ONE.subtract(price));
    }

    /** 这笔成交的费（USDT），四位小数 */
    public static BigDecimal commission(BigDecimal contracts, BigDecimal price) {
        return contracts.multiply(perShare(price)).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
