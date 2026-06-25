package com.mawai.wiibcommon.market;

import java.math.BigDecimal;

/**
 * 统一的 typed OHLCV bar（行情共享层，feed 写 / quant 读共用）。
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
