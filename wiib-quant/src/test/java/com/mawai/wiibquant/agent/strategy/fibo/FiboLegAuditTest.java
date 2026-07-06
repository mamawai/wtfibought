package com.mawai.wiibquant.agent.strategy.fibo;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.SwingDetector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FiboLegAuditTest {

    @Test
    void fractalPivotsFindsLocalExtremes() {
        // 上冲到 idx3(13) 再回落到 idx7(9)：唯一强局部高在 3、强局部低在 7
        List<KlineBar> bars = flat(10, 11, 12, 13, 12, 11, 10, 9, 10, 11, 12);
        List<SwingDetector.Pivot> p = FiboLegAudit.fractalPivots(bars, 2);
        assertEquals(2, p.size(), "应找到 1 高 1 低");
        assertEquals(3, p.get(0).barIndex());
        assertEquals(SwingDetector.PivotType.HIGH, p.get(0).type());
        assertEquals(7, p.get(1).barIndex());
        assertEquals(SwingDetector.PivotType.LOW, p.get(1).type());
    }

    @Test
    void alternateKeepsMoreExtremeOnConsecutiveSameType() {
        // LOW,HIGH,HIGH,LOW：两个连续 HIGH 应只留更高的(idx5=18)
        List<SwingDetector.Pivot> raw = List.of(
                new SwingDetector.Pivot(1, bd(10), SwingDetector.PivotType.LOW),
                new SwingDetector.Pivot(3, bd(15), SwingDetector.PivotType.HIGH),
                new SwingDetector.Pivot(5, bd(18), SwingDetector.PivotType.HIGH),
                new SwingDetector.Pivot(8, bd(9), SwingDetector.PivotType.LOW));
        List<SwingDetector.Pivot> alt = FiboLegAudit.alternate(raw);
        assertEquals(3, alt.size(), "连续同型归并后应 3 点交替");
        assertEquals(SwingDetector.PivotType.LOW, alt.get(0).type());
        assertEquals(5, alt.get(1).barIndex(), "两个 HIGH 保留更高的 idx5");
        assertEquals(0, alt.get(1).price().compareTo(bd(18)));
        assertEquals(SwingDetector.PivotType.LOW, alt.get(2).type());
    }

    @Test
    void legsBuildsAlternatingUpDown() {
        // 跌到 idx3(7) → 涨到 idx7(11) → 跌到 idx10(8)：一条上行腿 + 一条下行腿
        List<KlineBar> bars = flat(10, 9, 8, 7, 8, 9, 10, 11, 10, 9, 8, 9, 10);
        List<FiboLegAudit.Leg2> legs = FiboLegAudit.legs(bars, 2);
        assertEquals(2, legs.size());
        assertTrue(legs.get(0).upLeg(), "第一条应为上行腿");
        assertEquals(3, legs.get(0).startIdx());
        assertEquals(7, legs.get(0).endIdx());
        assertFalse(legs.get(1).upLeg(), "第二条应为下行腿");
        assertEquals(7, legs.get(1).startIdx());
        assertEquals(10, legs.get(1).endIdx());
    }

    @Test
    void aggregateRollsUp5mTo15m() {
        // 6 根 5m(300k 间隔) 价 10..15 → 2 根 15m：桶0[10,11,12] 桶1[13,14,15]
        List<KlineBar> b5 = new ArrayList<>();
        double[] px = {10, 11, 12, 13, 14, 15};
        for (int i = 0; i < 6; i++) {
            long t = i * 300_000L;
            BigDecimal v = bd(px[i]);
            b5.add(new KlineBar(t, t + 300_000L - 1, v, v, v, v, BigDecimal.ONE));
        }
        List<KlineBar> b15 = FiboLegAudit.aggregate(b5, 900_000L);
        assertEquals(2, b15.size(), "6 根 5m 应聚成 2 根 15m");
        assertEquals(0, b15.get(0).open().compareTo(bd(10)));
        assertEquals(0, b15.get(0).high().compareTo(bd(12)));
        assertEquals(0, b15.get(0).low().compareTo(bd(10)));
        assertEquals(0, b15.get(0).close().compareTo(bd(12)));
        assertEquals(0, b15.get(1).close().compareTo(bd(15)));
        assertEquals(0L, b15.get(0).openTime());
    }

    // ---- simulateRetracement：上行/下行各测，防方向 bug ----
    // 统一几何：range=100, entry0.66→pocket, sl0.88+0.1atr(atr=10)→stop, risk≈23, tp=前高/前低

    @Test
    void simulateUpLegWinReachesExtension() {
        FiboLegAudit.Leg2 leg = new FiboLegAudit.Leg2(0, 2, bd(100), bd(200), true);  // 上行腿 100→200
        List<KlineBar> bars = List.of(
                bar(100, 100, 100, 100), bar(150, 150, 150, 150), bar(200, 200, 200, 200),
                bar(140, 150, 133, 145),   // idx3: 探到口袋134成交
                bar(145, 205, 140, 200));  // idx4: 冲到前高200 → WIN
        FiboLegAudit.RetOutcome o = FiboLegAudit.simulateRetracement(leg, bars, 10, 0.66, 0.88, 0.1, 1.0, 50);
        assertTrue(o.touched());
        assertEquals(1, o.barsToPocket());
        assertEquals("WIN", o.outcome());
        assertEquals(3.09, o.mfeR(), 0.1, "最大顺势≈(205-134)/23");
    }

    @Test
    void simulateUpLegLossHitsStop() {
        FiboLegAudit.Leg2 leg = new FiboLegAudit.Leg2(0, 2, bd(100), bd(200), true);
        List<KlineBar> bars = List.of(
                bar(100, 100, 100, 100), bar(150, 150, 150, 150), bar(200, 200, 200, 200),
                bar(140, 138, 134, 135),   // idx3: 探到口袋134成交
                bar(135, 140, 110, 112));  // idx4: 跌破止损111 → LOSS
        FiboLegAudit.RetOutcome o = FiboLegAudit.simulateRetracement(leg, bars, 10, 0.66, 0.88, 0.1, 1.0, 50);
        assertTrue(o.touched());
        assertEquals("LOSS", o.outcome());
    }

    @Test
    void simulateUpLegNoneWhenNeverReachesPocket() {
        FiboLegAudit.Leg2 leg = new FiboLegAudit.Leg2(0, 2, bd(100), bd(200), true);
        List<KlineBar> bars = List.of(
                bar(100, 100, 100, 100), bar(150, 150, 150, 150), bar(200, 200, 200, 200),
                bar(180, 185, 180, 182), bar(182, 185, 160, 165));  // 从不跌到口袋134
        FiboLegAudit.RetOutcome o = FiboLegAudit.simulateRetracement(leg, bars, 10, 0.66, 0.88, 0.1, 1.0, 50);
        assertFalse(o.touched());
        assertEquals("NONE", o.outcome());
    }

    @Test
    void simulateDownLegWinReachesExtension() {
        FiboLegAudit.Leg2 leg = new FiboLegAudit.Leg2(0, 2, bd(200), bd(100), false);  // 下行腿 200→100
        List<KlineBar> bars = List.of(
                bar(200, 200, 200, 200), bar(150, 150, 150, 150), bar(100, 100, 100, 100),
                bar(150, 166, 150, 160),   // idx3: 反弹到口袋166成交(下行腿 high>=pocket)
                bar(120, 130, 95, 100));   // idx4: 跌到前低100 → WIN
        FiboLegAudit.RetOutcome o = FiboLegAudit.simulateRetracement(leg, bars, 10, 0.66, 0.88, 0.1, 1.0, 50);
        assertTrue(o.touched());
        assertEquals(1, o.barsToPocket());
        assertEquals("WIN", o.outcome());
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static KlineBar bar(double open, double high, double low, double close) {
        return new KlineBar(0, 899_999L, bd(open), bd(high), bd(low), bd(close), BigDecimal.ONE);
    }

    /** 造 n 根平 OHLC bar（open=high=low=close=价），15m 接续，便于精确控点。 */
    private static List<KlineBar> flat(double... px) {
        List<KlineBar> b = new ArrayList<>();
        for (int i = 0; i < px.length; i++) {
            long t = i * 900_000L;
            BigDecimal v = BigDecimal.valueOf(px[i]);
            b.add(new KlineBar(t, t + 900_000L - 1, v, v, v, v, BigDecimal.ONE));
        }
        return b;
    }
}
