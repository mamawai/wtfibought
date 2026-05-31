package com.mawai.wiibservice.agent.research.kline;

import java.math.BigDecimal;

/**
 * research 层统一的 typed OHLCV bar。
 * 时间为毫秒 epoch；价格/量为 BigDecimal（与全仓口径一致）。
 */
public record KlineBar(
        long openTime,
        long closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume
) {
}
