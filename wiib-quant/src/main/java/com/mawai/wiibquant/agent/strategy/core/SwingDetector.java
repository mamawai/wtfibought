package com.mawai.wiibquant.agent.strategy.core;

import com.mawai.wiibcommon.market.KlineBar;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 防重绘 ZigZag 摆动点检测。
 *
 * <p>核心约束：只输出"已确认"的 pivot——收盘价从极值反向走出 reversalAtrMult×ATR 后，
 * 该极值才成为 pivot；正在形成中的极值永不输出。回测与实盘在任意时点看到的 pivot 集合
 * 因此完全一致，杜绝 fibo 回测最常见的隐性未来函数。</p>
 */
public final class SwingDetector {

    public enum PivotType { HIGH, LOW }

    public record Pivot(int barIndex, BigDecimal price, PivotType type) {}

    private SwingDetector() {
    }

    public static List<Pivot> confirmedPivots(List<KlineBar> bars, int atrPeriod, double reversalAtrMult) {
        List<Pivot> pivots = new ArrayList<>();
        if (bars == null || bars.size() <= atrPeriod + 1) return pivots;
        double[] atr = atrSeries(bars, atrPeriod);

        int dir = 0; // 0=方向未定 1=上行段(找HIGH) -1=下行段(找LOW)
        BigDecimal extHigh = bars.get(atrPeriod).high();
        BigDecimal extLow = bars.get(atrPeriod).low();
        int extHighIdx = atrPeriod, extLowIdx = atrPeriod;

        for (int i = atrPeriod + 1; i < bars.size(); i++) {
            KlineBar b = bars.get(i);
            double close = b.close().doubleValue();
            double threshold = reversalAtrMult * atr[i];

            if (dir >= 0 && b.high().compareTo(extHigh) > 0) { extHigh = b.high(); extHighIdx = i; }
            if (dir <= 0 && b.low().compareTo(extLow) < 0) { extLow = b.low(); extLowIdx = i; }

            boolean confirmHigh = dir >= 0 && close <= extHigh.doubleValue() - threshold;
            boolean confirmLow = dir <= 0 && close >= extLow.doubleValue() + threshold;
            // 方向未定时两边同时满足（极端大波动）：取离当前更近的极值方向
            if (dir == 0 && confirmHigh && confirmLow) {
                if (extHighIdx >= extLowIdx) confirmLow = false;
                else confirmHigh = false;
            }

            if (confirmHigh) {
                pivots.add(new Pivot(extHighIdx, extHigh, PivotType.HIGH));
                dir = -1;
                // 重扫 (pivot, i]：真正的段内最低点可能出现在确认之前
                extLowIdx = Math.min(extHighIdx + 1, i);
                extLow = bars.get(extLowIdx).low();
                for (int j = extLowIdx + 1; j <= i; j++) {
                    if (bars.get(j).low().compareTo(extLow) < 0) { extLow = bars.get(j).low(); extLowIdx = j; }
                }
            } else if (confirmLow) {
                pivots.add(new Pivot(extLowIdx, extLow, PivotType.LOW));
                dir = 1;
                extHighIdx = Math.min(extLowIdx + 1, i);
                extHigh = bars.get(extHighIdx).high();
                for (int j = extHighIdx + 1; j <= i; j++) {
                    if (bars.get(j).high().compareTo(extHigh) > 0) { extHigh = bars.get(j).high(); extHighIdx = j; }
                }
            }
        }
        return pivots;
    }

    /** TR 的简单滑动均值；i < atrPeriod-1 为 NaN，调用方从 atrPeriod+1 开始消费，安全。 */
    public static double[] atrSeries(List<KlineBar> bars, int period) {
        int n = bars.size();
        double[] tr = new double[n];
        double[] atr = new double[n];
        tr[0] = bars.get(0).high().subtract(bars.get(0).low()).doubleValue();
        for (int i = 1; i < n; i++) {
            double h = bars.get(i).high().doubleValue();
            double l = bars.get(i).low().doubleValue();
            double pc = bars.get(i - 1).close().doubleValue();
            tr[i] = Math.max(h - l, Math.max(Math.abs(h - pc), Math.abs(l - pc)));
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += tr[i];
            if (i >= period) sum -= tr[i - period];
            atr[i] = i >= period - 1 ? sum / period : Double.NaN;
        }
        return atr;
    }
}
