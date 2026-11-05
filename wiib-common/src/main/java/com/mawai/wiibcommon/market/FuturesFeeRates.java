package com.mawai.wiibcommon.market;

import java.math.BigDecimal;

/**
 * 合约手续费率（按名义价值计）：maker 限价成交，taker 市价成交、止损止盈触发、强平。
 * sim 的 TradingConfig 默认值与 agent 开仓回执里的手续费估算同取这里。
 */
public final class FuturesFeeRates {

    public static final BigDecimal MAKER = new BigDecimal("0.0002");
    public static final BigDecimal TAKER = new BigDecimal("0.0004");

    private FuturesFeeRates() {
    }
}
