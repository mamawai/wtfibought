package com.mawai.wiibcommon.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * 时间加权均价（TWAP）：tick 之间按"上一价一直有效"计权。预测员拿它算末分钟已走过部分的现货均价。
 */
public final class TimeWeightedAverage {

    /** 一跳：时刻(ms) + 价 */
    public record Point(long timeMs, BigDecimal price) {
    }

    private TimeWeightedAverage() {
    }

    /**
     * 窗口 [fromMs, toMs] 内的 TWAP。points 按时间升序，可以带窗口外的点：窗口前最后一个价从 fromMs 起生效，
     * 恰好落在 toMs 的那一跳只当当前价不计权，toMs 之后的不看。窗口内外都没价回 null。
     */
    public static BigDecimal of(Collection<Point> points, long fromMs, long toMs) {
        if (points == null || points.isEmpty() || toMs <= fromMs) {
            return null;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        long covered = 0;
        BigDecimal current = null;
        long segmentStart = fromMs;
        for (Point p : points) {
            if (p.timeMs() <= fromMs) {
                current = p.price();   // 窗口前最后一个价，从 fromMs 起算
                continue;
            }
            if (p.timeMs() > toMs) {
                break;
            }
            if (current != null) {
                long dt = p.timeMs() - segmentStart;
                weighted = weighted.add(current.multiply(BigDecimal.valueOf(dt)));
                covered += dt;
            }
            current = p.price();
            segmentStart = p.timeMs();
        }
        if (current == null) {
            return null;
        }
        long dt = toMs - segmentStart;
        weighted = weighted.add(current.multiply(BigDecimal.valueOf(dt)));
        covered += dt;
        if (covered <= 0) {
            return current;
        }
        return weighted.divide(BigDecimal.valueOf(covered), 8, RoundingMode.HALF_UP);
    }
}
