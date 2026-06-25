package com.mawai.wiibquant.agent.strategy.fibo;

import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwingDetectorTest {

    private static final long BAR_MS = 14_400_000L;

    /** 等距bar：每根涨/跌 step，high=max(open,close)+1，low=min(open,close)-1（TR稳定，ATR可手算）。 */
    private static List<KlineBar> bars(double start, double... steps) {
        List<KlineBar> out = new ArrayList<>();
        double prev = start;
        long t = 0;
        for (double step : steps) {
            double open = prev, close = prev + step;
            double high = Math.max(open, close) + 1, low = Math.min(open, close) - 1;
            out.add(new KlineBar(t, t + BAR_MS - 1, bd(open), bd(high), bd(low), bd(close), BigDecimal.ONE));
            prev = close;
            t += BAR_MS;
        }
        return out;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static double[] steps(int n, double v) {
        double[] s = new double[n];
        java.util.Arrays.fill(s, v);
        return s;
    }

    private static double[] concat(double[]... arrays) {
        int len = 0;
        for (double[] a : arrays) len += a.length;
        double[] out = new double[len];
        int i = 0;
        for (double[] a : arrays) {
            System.arraycopy(a, 0, out, i, a.length);
            i += a.length;
        }
        return out;
    }

    @Test
    void confirmsHighPivotOnlyAfterReversalThreshold() {
        // 涨30根(+10/根) → 跌10根(-10/根)。ATR≈12，k=2 → 反转阈值≈24 → 跌2-3根后确认HIGH
        List<KlineBar> up30down10 = bars(100, concat(steps(30, 10), steps(10, -10)));
        List<SwingDetector.Pivot> pivots = SwingDetector.confirmedPivots(up30down10, 14, 2.0);
        assertFalse(pivots.isEmpty(), "回落超过2×ATR后应确认HIGH pivot");
        SwingDetector.Pivot last = pivots.getLast();
        assertEquals(SwingDetector.PivotType.HIGH, last.type());
        assertEquals(29, last.barIndex(), "HIGH应落在上涨末根(index 29)");
    }

    @Test
    void formingExtremeIsNeverEmitted() {
        // 只涨不跌：起点LOW会被上行2×ATR确认（这是上行腿的合法起点），
        // 但形成中的最高点（运行极值）永远不能作为HIGH输出——防重绘核心约束
        List<KlineBar> onlyUp = bars(100, steps(40, 10));
        List<SwingDetector.Pivot> pivots = SwingDetector.confirmedPivots(onlyUp, 14, 2.0);
        assertTrue(pivots.stream().noneMatch(p -> p.type() == SwingDetector.PivotType.HIGH),
                "形成中的最高点不许输出HIGH pivot");
    }

    @Test
    void alternatesHighLowAndRescansExtremeBetweenPivots() {
        // 涨30 → 跌20 → 涨20。检测从idx14(ATR预热后)开始：
        // LOW@14(上行起点,涨过2×ATR≈24确认) → HIGH@29(跌3根确认) → LOW@49(涨3根确认)，类型交替
        List<KlineBar> zigzag = bars(100, concat(steps(30, 10), steps(20, -10), steps(20, 10)));
        List<SwingDetector.Pivot> pivots = SwingDetector.confirmedPivots(zigzag, 14, 2.0);
        assertTrue(pivots.size() >= 3, "实际=" + pivots);
        assertEquals(SwingDetector.PivotType.LOW, pivots.get(0).type());
        assertEquals(14, pivots.get(0).barIndex(), "起点LOW落在检测起始bar");
        assertEquals(SwingDetector.PivotType.HIGH, pivots.get(1).type());
        assertEquals(29, pivots.get(1).barIndex(), "HIGH应落在上涨末根(index 29)");
        assertEquals(SwingDetector.PivotType.LOW, pivots.get(2).type());
        assertEquals(49, pivots.get(2).barIndex(), "LOW应落在下跌末根(index 49)");
    }

    @Test
    void sameBarReversalAtTailDoesNotReadPastEnd() {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long t = i * BAR_MS;
            bars.add(new KlineBar(t, t + BAR_MS - 1,
                    bd(100 + i * 2), bd(100 + i * 2 + 1),
                    bd(100 + i * 2 - 1), bd(100 + i * 2), BigDecimal.ONE));
        }
        long t = bars.size() * BAR_MS;
        bars.add(new KlineBar(t, t + BAR_MS - 1,
                bd(140), bd(141), bd(80), bd(140), BigDecimal.ONE));

        List<SwingDetector.Pivot> pivots = SwingDetector.confirmedPivots(bars, 3, 1.0);

        assertTrue(pivots.stream().anyMatch(p -> p.type() == SwingDetector.PivotType.LOW),
                "最后一根同时创低并拉回时，可以确认LOW但不能越界");
    }

}
