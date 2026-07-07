package com.mawai.wiibquant.agent.strategy.liq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 强平 fade 事件研究纯函数（测试域取证工具，不进生产）。
 *
 * <p>口径铁律：事件在 bar i 收盘判定，只用 ≤i 的已闭合数据——15m 滚动收益、premium 收盘、
 * taker 卖占比、前向填充的 OI；前向收益从 close[i] 起算。未来数据只出现在"评估事件结果"层，
 * 决策层无未来函数。所有滚动窗带时间连续性守卫，K线断档处直接 NaN、不跨档硬算。</p>
 */
public final class LiqFadeAudit {

    private LiqFadeAudit() {
    }

    /** 对齐后的 1m 主序列；缺失=NaN。t=openTime(ms) 升序。 */
    public record Series(long[] t, double[] high, double[] low, double[] close,
                         double[] vol, double[] takerBuy, double[] prem, double[] oi) {
        public int n() {
            return t.length;
        }
    }

    /** srcT 精确对齐到 masterT（两指针，均升序）；master 上无对应 src 行 → NaN。 */
    public static double[] alignByTime(long[] masterT, long[] srcT, double[] srcV) {
        double[] out = new double[masterT.length];
        Arrays.fill(out, Double.NaN);
        int j = 0;
        for (int i = 0; i < masterT.length; i++) {
            while (j < srcT.length && srcT[j] < masterT[i]) j++;
            if (j < srcT.length && srcT[j] == masterT[i]) out[i] = srcV[j];
        }
        return out;
    }

    /** 前向填充对齐：master 每根取 srcT ≤ 该根收盘时点(masterT[i]+barMs) 的最近值；此前无值 → NaN。 */
    public static double[] carryForward(long[] masterT, long barMs, long[] srcT, double[] srcV) {
        double[] out = new double[masterT.length];
        Arrays.fill(out, Double.NaN);
        int j = -1;
        for (int i = 0; i < masterT.length; i++) {
            long cutoff = masterT[i] + barMs;
            while (j + 1 < srcT.length && srcT[j + 1] <= cutoff) j++;
            if (j >= 0) out[i] = srcV[j];
        }
        return out;
    }

    /** k 根滚动变化率 v[i]/v[i-k]-1（收盘收益与 OI 骤降通用）；窗口跨断档（时距≠k×barMs）或基值无效 → NaN。 */
    public static double[] rollingRet(long[] t, double[] v, int k, long barMs) {
        double[] out = new double[t.length];
        Arrays.fill(out, Double.NaN);
        for (int i = k; i < t.length; i++) {
            if (t[i] - t[i - k] != k * barMs) continue;
            double base = v[i - k];
            if (!(base > 0) || Double.isNaN(v[i])) continue;
            out[i] = v[i] / base - 1;
        }
        return out;
    }

    /** k 根量加权 taker 卖占比 = 1-Σbuy/Σvol，窗口 [i-k+1, i]；跨断档/缺数/零总量 → NaN。 */
    public static double[] rollingSellShare(long[] t, double[] vol, double[] takerBuy, int k, long barMs) {
        double[] out = new double[t.length];
        Arrays.fill(out, Double.NaN);
        for (int i = k - 1; i < t.length; i++) {
            if (t[i] - t[i - k + 1] != (long) (k - 1) * barMs) continue;
            double v = 0, b = 0;
            boolean ok = true;
            for (int j = i - k + 1; j <= i; j++) {
                if (Double.isNaN(vol[j]) || Double.isNaN(takerBuy[j])) {
                    ok = false;
                    break;
                }
                v += vol[j];
                b += takerBuy[j];
            }
            if (ok && v > 0) out[i] = 1 - b / v;
        }
        return out;
    }

    /**
     * 1m 主序列聚合到 barMin 分钟决策粒度（实盘 5m 收盘驱动的口径复验用）：
     * OHLC 常规、vol/takerBuy 桶内累加（含 NaN 则 NaN 传染）、prem/oi 取桶内最后非 NaN
     * （=该决策 bar 收盘时点能看到的最新值）；只输出完整桶（凑满 barMin 根），残桶丢弃。
     */
    public static Series aggregate(Series m1, int barMin) {
        long tfMs = barMin * 60_000L;
        int n = m1.n();
        long[] t = new long[n];
        double[] high = new double[n], low = new double[n], close = new double[n],
                vol = new double[n], taker = new double[n], prem = new double[n], oi = new double[n];
        int m = 0, i = 0;
        while (i < n) {
            long bucket = Math.floorDiv(m1.t()[i], tfMs) * tfMs;
            double h = Double.NEGATIVE_INFINITY, l = Double.POSITIVE_INFINITY, c = Double.NaN;
            double v = 0, b = 0, p = Double.NaN, o = Double.NaN;
            int cnt = 0, j = i;
            while (j < n && Math.floorDiv(m1.t()[j], tfMs) * tfMs == bucket) {
                h = Math.max(h, m1.high()[j]);
                l = Math.min(l, m1.low()[j]);
                c = m1.close()[j];
                v += m1.vol()[j];
                b += m1.takerBuy()[j];
                if (!Double.isNaN(m1.prem()[j])) p = m1.prem()[j];
                if (!Double.isNaN(m1.oi()[j])) o = m1.oi()[j];
                cnt++;
                j++;
            }
            if (cnt == barMin) {
                t[m] = bucket;
                high[m] = h;
                low[m] = l;
                close[m] = c;
                vol[m] = v;
                taker[m] = b;
                prem[m] = p;
                oi[m] = o;
                m++;
            }
            i = j;
        }
        return new Series(Arrays.copyOf(t, m), Arrays.copyOf(high, m), Arrays.copyOf(low, m),
                Arrays.copyOf(close, m), Arrays.copyOf(vol, m), Arrays.copyOf(taker, m),
                Arrays.copyOf(prem, m), Arrays.copyOf(oi, m));
    }

    /** [from,to) 段内非 NaN 值的 q 分位（最近秩法）；空段 → NaN。 */
    public static double quantile(double[] v, long[] t, long from, long to, double q) {
        double[] buf = new double[v.length];
        int m = 0;
        for (int i = 0; i < v.length; i++) {
            if (t[i] < from || t[i] >= to || Double.isNaN(v[i])) continue;
            buf[m++] = v[i];
        }
        if (m == 0) return Double.NaN;
        double[] cut = Arrays.copyOf(buf, m);
        Arrays.sort(cut);
        int idx = (int) Math.round(q * (m - 1));
        return cut[Math.max(0, Math.min(m - 1, idx))];
    }

    /**
     * 一次事件：全部特征在 idx 收盘时点可观测；fwd/mfe/mae 为 <b>fade 方向收益</b>
     * （longSide=false 的做空侧已取反，正数=对 fade 有利）。
     */
    public record Event(int idx, long time, boolean longSide, int flags,
                        boolean flushHit, boolean premHit, boolean takerHit,
                        double flushRet, double prem, double sellShare, double oiChg,
                        double[] fwd, double mfe, double mae) {
    }

    /**
     * 事件检测：三签名（价格瀑布/premium 错位/taker 卖压）至少 minFlags 个命中即触发，
     * cooldown 根内不再触发（同一场瀑布只记首触，保守且免重叠样本）。
     * longSide=true 检下杀（fade=做多，阈值为左尾）；false 检上冲（fade=做空，调用方给右尾阈值）。
     * 尾部不足最大前向窗的 bar 不检（保证每个事件前向数据完整）。NaN 比较自然为 false=未命中。
     */
    public static List<Event> detect(Series s, double[] ret, double[] sell, double[] oiChg,
                                     boolean longSide, double thFlush, double thPrem, double thSell,
                                     int minFlags, int cooldownBars, int[] horizons, int mfeWindow) {
        int maxH = mfeWindow;
        for (int h : horizons) maxH = Math.max(maxH, h);
        List<Event> out = new ArrayList<>();
        int last = Integer.MIN_VALUE / 2;
        for (int i = 0; i + maxH < s.n(); i++) {
            boolean flush = longSide ? ret[i] <= thFlush : ret[i] >= thFlush;
            boolean premH = longSide ? s.prem()[i] <= thPrem : s.prem()[i] >= thPrem;
            boolean takerH = longSide ? sell[i] >= thSell : sell[i] <= thSell;
            int flags = (flush ? 1 : 0) + (premH ? 1 : 0) + (takerH ? 1 : 0);
            if (flags < minFlags || i - last < cooldownBars) continue;
            double c0 = s.close()[i];
            if (!(c0 > 0)) continue;
            last = i;
            double[] fwd = new double[horizons.length];
            for (int j = 0; j < horizons.length; j++) {
                double raw = s.close()[i + horizons[j]] / c0 - 1;
                fwd[j] = longSide ? raw : -raw;
            }
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            for (int j = i + 1; j <= i + mfeWindow; j++) {
                hi = Math.max(hi, s.high()[j]);
                lo = Math.min(lo, s.low()[j]);
            }
            double mfe = longSide ? hi / c0 - 1 : 1 - lo / c0;
            double mae = longSide ? lo / c0 - 1 : 1 - hi / c0;
            out.add(new Event(i, s.t()[i], longSide, flags, flush, premH, takerH,
                    ret[i], s.prem()[i], sell[i], oiChg[i], fwd, mfe, mae));
        }
        return out;
    }

    /** 样本聚合：n/均值/中位/胜率/t 值（NaN 剔除；t 按独立样本口径，簇效应见 runner 提示）。 */
    public record Agg(int n, double mean, double median, double winPct, double tStat) {
    }

    public static Agg agg(double[] xs) {
        double[] clean = Arrays.stream(xs).filter(x -> !Double.isNaN(x)).toArray();
        int n = clean.length;
        if (n == 0) return new Agg(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        double mean = Arrays.stream(clean).average().orElse(Double.NaN);
        double[] sorted = clean.clone();
        Arrays.sort(sorted);
        double med = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
        long wins = Arrays.stream(clean).filter(x -> x > 0).count();
        double var = 0;
        for (double x : clean) var += (x - mean) * (x - mean);
        double sd = n > 1 ? Math.sqrt(var / (n - 1)) : Double.NaN;
        double t = n > 1 && sd > 0 ? mean / (sd / Math.sqrt(n)) : Double.NaN;
        return new Agg(n, mean, med, 100.0 * wins / n, t);
    }

    /** 无条件基线：段内每根 bar 的 h 根前向收益（fade 方向口径，short 侧取反）。 */
    public static Agg baseline(Series s, int h, long from, long to, boolean longSide) {
        double[] buf = new double[s.n()];
        int m = 0;
        for (int i = 0; i + h < s.n(); i++) {
            if (s.t()[i] < from || s.t()[i] >= to) continue;
            double c0 = s.close()[i];
            if (!(c0 > 0)) continue;
            double raw = s.close()[i + h] / c0 - 1;
            buf[m++] = longSide ? raw : -raw;
        }
        return agg(Arrays.copyOf(buf, m));
    }
}
