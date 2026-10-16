package com.mawai.wiibcommon.market;

import java.math.BigDecimal;
import java.util.List;

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

    /** 去掉末尾还在走的那根（closeTime 还没到）：REST 拉的最新页末根多半是它 */
    public static List<KlineBar> closed(List<KlineBar> bars, long nowMs) {
        return !bars.isEmpty() && bars.getLast().closeTime() >= nowMs
                ? bars.subList(0, bars.size() - 1) : bars;
    }

    /**
     * 只取 {@code closeTime > sinceMs} 的 bar 算 [low, high]，一根都没有返回 null。
     * since 落在哪根里面，那根整根算进来——宁多不漏，误差 ≤1 分钟。
     */
    public static BigDecimal[] lowHighAfter(List<KlineBar> bars, long sinceMs) {
        BigDecimal low = null;
        BigDecimal high = null;
        for (KlineBar b : bars) {
            if (b.closeTime() <= sinceMs) continue;
            if (low == null || b.low().compareTo(low) < 0) low = b.low();
            if (high == null || b.high().compareTo(high) > 0) high = b.high();
        }
        return low == null ? null : new BigDecimal[]{low, high};
    }
}
