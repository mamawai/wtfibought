package com.mawai.wiibquant.agent.strategy.liq;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LiqFadeAuditTest {

    private static final long BAR = 60_000L;

    private static long[] times(int n) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = i * BAR;
        return t;
    }

    private static double[] fill(int n, double v) {
        double[] a = new double[n];
        Arrays.fill(a, v);
        return a;
    }

    @Test
    void alignByTime_缺行为NaN() {
        long[] master = {0, BAR, 2 * BAR};
        long[] src = {0, 2 * BAR};
        double[] v = {1.0, 3.0};
        double[] out = LiqFadeAudit.alignByTime(master, src, v);
        assertThat(out[0]).isEqualTo(1.0);
        assertThat(out[1]).isNaN();
        assertThat(out[2]).isEqualTo(3.0);
    }

    @Test
    void carryForward_收盘时点前向填充() {
        long[] master = {0, BAR, 2 * BAR};
        long[] src = {110_000L};          // 落在 bar1 (60000~120000] 收盘前
        double[] v = {7.0};
        double[] out = LiqFadeAudit.carryForward(master, BAR, src, v);
        assertThat(out[0]).isNaN();       // bar0 收盘 60000 < 110000, 还看不到
        assertThat(out[1]).isEqualTo(7.0);
        assertThat(out[2]).isEqualTo(7.0);
    }

    @Test
    void rollingRet_取值与断档守卫() {
        double[] close = fill(40, 100);
        close[25] = 105;
        double[] ret = LiqFadeAudit.rollingRet(times(40), close, 15, BAR);
        assertThat(ret[25]).isCloseTo(0.05, within(1e-12));
        assertThat(ret[10]).isNaN();      // 不足 15 根

        long[] gapped = times(40);
        for (int i = 20; i < 40; i++) gapped[i] += BAR;   // 20 处断一根
        double[] ret2 = LiqFadeAudit.rollingRet(gapped, close, 15, BAR);
        assertThat(ret2[25]).isNaN();     // 窗口跨断档
        assertThat(ret2[36]).isCloseTo(0.0, within(1e-12));   // 断档之后的干净窗口恢复
    }

    @Test
    void rollingSellShare_量加权与NaN守卫() {
        int n = 30;
        double[] vol = fill(n, 2.0);
        double[] buy = fill(n, 0.5);
        double[] share = LiqFadeAudit.rollingSellShare(times(n), vol, buy, 15, BAR);
        assertThat(share[14]).isCloseTo(0.75, within(1e-12));
        assertThat(share[13]).isNaN();

        double[] buy2 = fill(n, 0.5);
        buy2[20] = Double.NaN;
        double[] share2 = LiqFadeAudit.rollingSellShare(times(n), vol, buy2, 15, BAR);
        assertThat(share2[20]).isNaN();   // 窗口含缺数
        assertThat(share2[14]).isCloseTo(0.75, within(1e-12));
    }

    @Test
    void quantile_段内最近秩分位() {
        int n = 100;
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = i + 1;   // 1..100
        long[] t = times(n);
        assertThat(LiqFadeAudit.quantile(v, t, 0, n * BAR, 0.05)).isEqualTo(6.0);    // round(4.95)=5 → 第6个
        assertThat(LiqFadeAudit.quantile(v, t, 0, n * BAR, 0.5)).isEqualTo(51.0);    // round(49.5)=50
        assertThat(LiqFadeAudit.quantile(v, t, 0, 10 * BAR, 1.0)).isEqualTo(10.0);   // 段内截断
    }

    /** 长侧：下杀+premium 错位双签名触发；冷却抑制第二场；前向/MFE/MAE 手算核对。 */
    @Test
    void detect_长侧_冷却_前向() {
        int n = 400;
        long[] t = times(n);
        double[] close = fill(n, 100);
        for (int i = 100; i < n; i++) close[i] = 98;    // bar100: 15m 收益 -2%
        for (int i = 130; i < n; i++) close[i] = 96;    // bar130: 相对 bar115(98) 再 -2.04%
        double[] high = new double[n], low = new double[n];
        for (int i = 0; i < n; i++) {
            high[i] = close[i] + 0.5;
            low[i] = close[i] - 0.5;
        }
        double[] vol = fill(n, 1.0), buy = fill(n, 0.5);   // 卖占比恒 0.5, taker 签名不触发
        double[] prem = fill(n, 0.0);
        prem[100] = -0.001;
        prem[130] = -0.001;
        LiqFadeAudit.Series s = new LiqFadeAudit.Series(t, high, low, close, vol, buy, prem, fill(n, Double.NaN));
        double[] ret15 = LiqFadeAudit.rollingRet(t, close, 15, BAR);
        double[] sell15 = LiqFadeAudit.rollingSellShare(t, vol, buy, 15, BAR);
        double[] oiChg = fill(n, Double.NaN);
        int[] horizons = {15, 60};

        List<LiqFadeAudit.Event> evs = LiqFadeAudit.detect(s, ret15, sell15, oiChg,
                true, -0.015, -0.0005, 0.9, 2, 60, horizons, 60);
        assertThat(evs).hasSize(1);                     // bar130 在冷却 60 根内被抑制
        LiqFadeAudit.Event e = evs.getFirst();
        assertThat(e.idx()).isEqualTo(100);
        assertThat(e.flags()).isEqualTo(2);
        assertThat(e.flushHit()).isTrue();
        assertThat(e.premHit()).isTrue();
        assertThat(e.takerHit()).isFalse();
        assertThat(e.fwd()[0]).isCloseTo(0.0, within(1e-12));              // close[115]=98
        assertThat(e.fwd()[1]).isCloseTo(96.0 / 98 - 1, within(1e-12));    // close[160]=96
        assertThat(e.mfe()).isCloseTo(98.5 / 98 - 1, within(1e-12));       // (100,160] 最高 98.5
        assertThat(e.mae()).isCloseTo(95.5 / 98 - 1, within(1e-12));       // (100,160] 最低 95.5

        List<LiqFadeAudit.Event> evs2 = LiqFadeAudit.detect(s, ret15, sell15, oiChg,
                true, -0.015, -0.0005, 0.9, 2, 20, horizons, 60);
        assertThat(evs2).hasSize(2);                    // 冷却缩到 20 根, bar130 独立成事件
        assertThat(evs2.get(1).idx()).isEqualTo(130);
    }

    /** 短侧对称：上冲+正向 premium；fade 收益已取反（正=做空有利）。 */
    @Test
    void detect_短侧对称取反() {
        int n = 400;
        long[] t = times(n);
        double[] close = fill(n, 100);
        for (int i = 100; i < n; i++) close[i] = 102;
        double[] high = new double[n], low = new double[n];
        for (int i = 0; i < n; i++) {
            high[i] = close[i] + 0.5;
            low[i] = close[i] - 0.5;
        }
        double[] vol = fill(n, 1.0), buy = fill(n, 0.5);
        double[] prem = fill(n, 0.0);
        prem[100] = 0.001;
        LiqFadeAudit.Series s = new LiqFadeAudit.Series(t, high, low, close, vol, buy, prem, fill(n, Double.NaN));
        double[] ret15 = LiqFadeAudit.rollingRet(t, close, 15, BAR);
        double[] sell15 = LiqFadeAudit.rollingSellShare(t, vol, buy, 15, BAR);

        List<LiqFadeAudit.Event> evs = LiqFadeAudit.detect(s, ret15, sell15, fill(n, Double.NaN),
                false, 0.015, 0.0005, 0.1, 2, 60, new int[]{15, 60}, 60);
        assertThat(evs).hasSize(1);
        LiqFadeAudit.Event e = evs.getFirst();
        assertThat(e.idx()).isEqualTo(100);
        assertThat(e.fwd()[0]).isCloseTo(0.0, within(1e-12));               // 价平, fade 收益 0
        assertThat(e.mfe()).isCloseTo(1 - 101.5 / 102, within(1e-12));      // 空头顺势=低点方向
        assertThat(e.mae()).isCloseTo(1 - 102.5 / 102, within(1e-12));      // 空头逆势=高点方向
    }

    @Test
    void aggregate_5m聚合口径() {
        // 12 根 1m → 2 个完整 5m 桶，残桶(2根)丢弃；prem 取桶内最后非 NaN
        int n = 12;
        long[] t = times(n);
        double[] close = new double[n], high = new double[n], low = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = 100 + i;
            high[i] = close[i] + 1;
            low[i] = close[i] - 1;
        }
        double[] prem = fill(n, Double.NaN);
        prem[3] = -0.001;
        prem[8] = 0.002;
        LiqFadeAudit.Series m1 = new LiqFadeAudit.Series(t, high, low, close,
                fill(n, 2), fill(n, 0.5), prem, fill(n, Double.NaN));
        LiqFadeAudit.Series s5 = LiqFadeAudit.aggregate(m1, 5);
        assertThat(s5.n()).isEqualTo(2);
        assertThat(s5.t()[0]).isEqualTo(0);
        assertThat(s5.t()[1]).isEqualTo(5 * BAR);
        assertThat(s5.close()[0]).isEqualTo(104.0);
        assertThat(s5.high()[0]).isEqualTo(105.0);
        assertThat(s5.low()[0]).isEqualTo(99.0);
        assertThat(s5.vol()[0]).isEqualTo(10.0);
        assertThat(s5.takerBuy()[0]).isEqualTo(2.5);
        assertThat(s5.prem()[0]).isEqualTo(-0.001);
        assertThat(s5.prem()[1]).isEqualTo(0.002);
        assertThat(s5.oi()[0]).isNaN();
    }

    @Test
    void agg_基本统计() {
        LiqFadeAudit.Agg a = LiqFadeAudit.agg(new double[]{1, 2, 3, -1, Double.NaN});
        assertThat(a.n()).isEqualTo(4);
        assertThat(a.mean()).isCloseTo(1.25, within(1e-12));
        assertThat(a.median()).isCloseTo(1.5, within(1e-12));
        assertThat(a.winPct()).isCloseTo(75.0, within(1e-12));
        assertThat(a.tStat()).isCloseTo(1.4638, within(1e-3));
    }

    @Test
    void baseline_段内无条件前向() {
        int n = 50;
        double[] close = new double[n];
        for (int i = 0; i < n; i++) close[i] = 100 + i;   // 单调上涨
        LiqFadeAudit.Series s = new LiqFadeAudit.Series(times(n), close, close, close,
                fill(n, 1), fill(n, 0.5), fill(n, 0), fill(n, Double.NaN));
        LiqFadeAudit.Agg longBase = LiqFadeAudit.baseline(s, 10, 0, n * BAR, true);
        LiqFadeAudit.Agg shortBase = LiqFadeAudit.baseline(s, 10, 0, n * BAR, false);
        assertThat(longBase.n()).isEqualTo(40);
        assertThat(longBase.mean()).isGreaterThan(0);
        assertThat(shortBase.mean()).isCloseTo(-longBase.mean(), within(1e-12));
    }
}
