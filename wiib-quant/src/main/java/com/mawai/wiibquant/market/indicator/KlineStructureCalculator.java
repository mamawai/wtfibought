package com.mawai.wiibquant.market.indicator;

import com.mawai.wiibcommon.market.KlineBar;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * K 线结构摘要（中性事实层）。给 LLM 看"整段路径长什么样"，与 {@link CryptoIndicatorCalculator}
 * 分工：那边算末根的点状态（RSI/MACD/BOLL），这边算高低路径、分段量价、关键位。
 * <p>
 * <b>只出可复算的数字，不出判读词</b>。没有"空头主导""筑底完成"这类结论——
 * trader 的交易思路是用户从提示词灌进来的（缠论、道氏、量价各家都有），
 * 计算器每替它下一个结论，就把别派的人挡在门外。参数全部开放给调用方（模型）传，
 * swing 半窗取 3 还是 12、量能分多少箱，那是它的流派，不是我们的。
 * <p>
 * ATR 一律走 {@link CryptoIndicatorCalculator#atr}（Wilder/RMA），与 indicators 工具同源同值。
 * 本类另出的 {@code avg_tr_*} 是 TR 的算术平均，是另一回事——它记忆短、贴当下，
 * 但<b>不叫 ATR</b>：同名不同值会让模型无从判断该信谁，而 ATR 常被拿去算止损距离，
 * 口径差一截就是真金白银的风险偏差。
 */
public final class KlineStructureCalculator {

    /** 中间计算精度，够覆盖价格/量/比率三种量级 */
    private static final int CALC_SCALE = 10;
    /**
     * 价格与量的输出精度：有效数字而非固定小数位，与 {@link CryptoIndicatorCalculator} 同策略。
     * 标的从 BTC(6万) 到 DOGE(0.1) 横跨六个数量级，固定 4 位小数会把 0.10234 切成 0.1023，
     * 而 {@code klines} 给的是 Binance 原始精度，两边对照就对不上。
     * 10 位也让 ATR 原样透传，与 indicators 的 atr14 逐位相同。
     */
    private static final MathContext OUT_MC = new MathContext(10, RoundingMode.HALF_UP);
    /** 比率/百分比：量级本就已知（多在 ±100 内），固定 6 位小数比有效数字更短也更好读 */
    private static final int RATIO_SCALE = 6;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private KlineStructureCalculator() {
    }

    /**
     * 计算参数。全部开放给模型传，默认值只是"没意见时的选择"。
     *
     * @param swingWindow       局部极值半窗。左右各 N 根都不超过它才算 swing；
     *                          <b>末尾 N 根永远不会被标为 swing</b>——右侧确认还没走完，
     *                          这是所有防重绘摆动检测的固有代价，不是漏算
     * @param equalSegments     均匀分段数
     * @param lastN             近端窗口，管均量/波动/focus.last
     * @param focusRadius       关键点附近取数半径
     * @param topVolumeBars     放量 Top N
     * @param maPeriods         SMA 周期表
     * @param atrPeriod         ATR 周期（走 Wilder RMA）
     * @param volBinCount       价位量能分箱数，≤0 关闭
     * @param volBinTop         返回量最大的前 N 箱
     * @param recentSwingLevels 输出最近多少个 swing 高/低价
     */
    public record Params(int swingWindow, int equalSegments, int lastN, int focusRadius,
                         int topVolumeBars, List<Integer> maPeriods, int atrPeriod,
                         int volBinCount, int volBinTop, int recentSwingLevels) {

        public static Params defaults() {
            return new Params(6, 3, 20, 3, 8, List.of(20, 60), 14, 24, 8, 5);
        }
    }

    /** 局部极值点。type: H=局部高点 / L=局部低点（同一根可能两者都是——它的振幅罩住了整个窗口） */
    private record Swing(int idx, String type, BigDecimal price, long timeMs, BigDecimal close) {
    }

    // ==================== 主入口 ====================

    public static Map<String, Object> compute(List<KlineBar> bars, Params p) {
        List<String> errors = validate(bars);
        if (!errors.isEmpty()) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("errors", errors);
            bad.put("warnings", List.of());
            bad.put("meta", Map.of("bar_count", bars == null ? 0 : bars.size()));
            return bad;
        }

        int n = bars.size();
        List<String> warnings = new ArrayList<>();
        long[] times = new long[n];
        List<BigDecimal> opens = new ArrayList<>(n), highs = new ArrayList<>(n),
                lows = new ArrayList<>(n), closes = new ArrayList<>(n), vols = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            KlineBar b = bars.get(i);
            times[i] = b.openTime();
            opens.add(b.open());
            highs.add(b.high());
            lows.add(b.low());
            closes.add(b.close());
            vols.add(b.volume());
        }
        collectWarnings(bars, n, p, warnings);

        BigDecimal volTotal = sum(vols, 0, n);
        BigDecimal volAvg = divide(volTotal, BigDecimal.valueOf(n));
        List<Swing> swings = findSwings(highs, lows, closes, times, p.swingWindow());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("errors", List.of());
        out.put("warnings", warnings);
        out.put("meta", buildMeta(n, times, p));
        out.put("range", buildRange(opens, highs, lows, closes, times, n));
        out.put("bar_stats", buildBarStats(opens, highs, lows, closes, n));
        out.put("swings", swings.stream().map(KlineStructureCalculator::swingMap).toList());
        out.put("segments_equal", buildEqualSegments(n, p.equalSegments(), opens, highs, lows, closes, vols, volAvg));
        out.put("segments_swing", buildSwingSegments(swings, n, opens, highs, lows, closes, vols, volAvg));
        out.put("volume", buildVolume(opens, highs, lows, closes, vols, times, n, p, volTotal, volAvg));
        out.put("volatility", buildVolatility(highs, lows, closes, n, p, warnings));
        out.put("ma_context", buildMaContext(closes, p, warnings));
        out.put("levels", buildLevels(highs, lows, vols, swings, p));
        out.put("focus_bars", buildFocusBars(bars, highs, lows, vols, n, p));
        return out;
    }

    // ==================== 校验 ====================

    /** 硬错误：结构不可用，直接返回。软问题走 warnings。 */
    private static List<String> validate(List<KlineBar> bars) {
        List<String> errors = new ArrayList<>();
        if (bars == null) {
            return List.of("bars is null");
        }
        if (bars.isEmpty()) {
            return List.of("bars is empty");
        }
        for (int i = 0; i < bars.size(); i++) {
            KlineBar b = bars.get(i);
            if (b == null || b.open() == null || b.high() == null
                    || b.low() == null || b.close() == null || b.volume() == null) {
                errors.add("bar[" + i + "] has null field");
            } else {
                if (b.high().compareTo(b.low()) < 0) {
                    errors.add("bar[" + i + "] high < low");
                }
                if (b.volume().signum() < 0) {
                    errors.add("bar[" + i + "] volume < 0");
                }
            }
            if (errors.size() >= 5) {
                errors.add("...");
                break;
            }
        }
        return errors;
    }

    /**
     * 软问题。OHLC 自洽性放这里而不是 errors：交易所偶发脏数据不该让整个摘要不可用，
     * 但模型有权知道这段数据有几根不自洽。
     */
    private static void collectWarnings(List<KlineBar> bars, int n, Params p, List<String> warnings) {
        if (n < 30) {
            warnings.add("short_series:bar_count=" + n);
        }
        if (p.swingWindow() * 2 + 1 > n) {
            warnings.add("swing_window_too_large_for_series");
        }
        int inconsistent = 0;
        for (KlineBar b : bars) {
            BigDecimal bodyHigh = b.open().max(b.close());
            BigDecimal bodyLow = b.open().min(b.close());
            if (b.high().compareTo(bodyHigh) < 0 || b.low().compareTo(bodyLow) > 0) {
                inconsistent++;
            }
        }
        if (inconsistent > 0) {
            warnings.add("ohlc_inconsistent_bars=" + inconsistent);
        }
    }

    // ==================== meta / range / bar_stats ====================

    /**
     * 只留读不出来的。分段数、Top N 条数、focus 半径这些看数组长度就知道，
     * atr 周期写在字段名里，回显一遍纯占地方；
     * swing_window 得留——swings 是"左右各 N 根内的极值"，这个 N 从结果反推不出来。
     */
    private static Map<String, Object> buildMeta(int n, long[] times, Params p) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("bar_count", n);
        meta.put("start_time_ms", times[0]);
        meta.put("end_time_ms", times[n - 1]);
        // 两个 openTime 之差，不含末根自身周期——要总时长得再加一个 bar 的周期
        meta.put("duration_ms", times[n - 1] - times[0]);
        meta.put("swing_window", p.swingWindow());
        return meta;
    }

    private static Map<String, Object> buildRange(List<BigDecimal> opens, List<BigDecimal> highs,
                                                  List<BigDecimal> lows, List<BigDecimal> closes,
                                                  long[] times, int n) {
        BigDecimal o0 = opens.getFirst();
        BigDecimal cN = closes.get(n - 1);
        BigDecimal change = cN.subtract(o0);
        int hiIdx = extremeIdx(highs, true);
        int loIdx = extremeIdx(lows, false);
        BigDecimal hi = highs.get(hiIdx);
        BigDecimal lo = lows.get(loIdx);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("open", price(o0));
        m.put("close", price(cN));
        m.put("change", price(change));
        m.put("change_pct", pct(change, o0));
        m.put("high", price(hi));
        m.put("low", price(lo));
        m.put("high_idx", hiIdx);
        m.put("low_idx", loIdx);
        m.put("high_time_ms", times[hiIdx]);
        m.put("low_time_ms", times[loIdx]);
        m.put("amplitude", price(hi.subtract(lo)));
        m.put("amplitude_pct", pct(hi.subtract(lo), o0));
        return m;
    }

    private static Map<String, Object> buildBarStats(List<BigDecimal> opens, List<BigDecimal> highs,
                                                     List<BigDecimal> lows, List<BigDecimal> closes, int n) {
        int up = 0, down = 0, flat = 0;
        BigDecimal bodySum = BigDecimal.ZERO, rangeSum = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            int cmp = closes.get(i).compareTo(opens.get(i));
            if (cmp > 0) {
                up++;
            } else if (cmp < 0) {
                down++;
            } else {
                flat++;
            }
            bodySum = bodySum.add(closes.get(i).subtract(opens.get(i)).abs());
            rangeSum = rangeSum.add(highs.get(i).subtract(lows.get(i)));
        }
        BigDecimal cnt = BigDecimal.valueOf(n);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("up_bars", up);
        m.put("down_bars", down);
        m.put("flat_bars", flat);
        m.put("avg_body", price(divide(bodySum, cnt)));
        m.put("avg_range", price(divide(rangeSum, cnt)));
        return m;
    }

    // ==================== swings ====================

    /**
     * 局部极值：[i-w, i+w] 窗口内 high 最大记 H、low 最小记 L。
     * 边界 w 根不参与——窗口不完整，判不了。
     */
    private static List<Swing> findSwings(List<BigDecimal> highs, List<BigDecimal> lows,
                                          List<BigDecimal> closes, long[] times, int window) {
        List<Swing> out = new ArrayList<>();
        int n = highs.size();
        if (window <= 0 || n < window * 2 + 1) {
            return out;
        }
        for (int i = window; i < n - window; i++) {
            boolean isHigh = true, isLow = true;
            for (int j = i - window; j <= i + window; j++) {
                if (highs.get(j).compareTo(highs.get(i)) > 0) {
                    isHigh = false;
                }
                if (lows.get(j).compareTo(lows.get(i)) < 0) {
                    isLow = false;
                }
                if (!isHigh && !isLow) {
                    break;
                }
            }
            // H 固定排在 L 前：同一根两者都成立时（振幅罩住整窗）顺序得稳定，结果才可复现
            if (isHigh) {
                out.add(new Swing(i, "H", highs.get(i), times[i], closes.get(i)));
            }
            if (isLow) {
                out.add(new Swing(i, "L", lows.get(i), times[i], closes.get(i)));
            }
        }
        return out;
    }

    private static Map<String, Object> swingMap(Swing s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idx", s.idx());
        m.put("type", s.type());
        m.put("price", price(s.price()));
        m.put("time_ms", s.timeMs());
        m.put("close", price(s.close()));
        return m;
    }

    // ==================== segments ====================

    /** 半开区间 [i0, i1) 的统计。 */
    private static Map<String, Object> segmentStats(int i0, int i1, List<BigDecimal> opens,
                                                    List<BigDecimal> highs, List<BigDecimal> lows,
                                                    List<BigDecimal> closes, List<BigDecimal> vols,
                                                    BigDecimal globalVolAvg) {
        BigDecimal o = opens.get(i0);
        BigDecimal c = closes.get(i1 - 1);
        BigDecimal h = highs.get(i0), l = lows.get(i0);
        for (int i = i0 + 1; i < i1; i++) {
            h = h.max(highs.get(i));
            l = l.min(lows.get(i));
        }
        int cnt = i1 - i0;
        BigDecimal vsum = sum(vols, i0, i1);
        BigDecimal vavg = divide(vsum, BigDecimal.valueOf(cnt));
        BigDecimal ch = c.subtract(o);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("i0", i0);
        m.put("i1", i1);
        m.put("bar_count", cnt);
        m.put("open", price(o));
        m.put("close", price(c));
        m.put("change", price(ch));
        m.put("change_pct", pct(ch, o));
        m.put("high", price(h));
        m.put("low", price(l));
        m.put("volume_sum", volume(vsum));
        m.put("volume_avg", volume(vavg));
        m.put("volume_ratio", globalVolAvg.signum() > 0
                ? divide(vavg, globalVolAvg).setScale(RATIO_SCALE, RoundingMode.HALF_UP) : null);
        return m;
    }

    private static List<Map<String, Object>> buildEqualSegments(int n, int k, List<BigDecimal> opens,
                                                                List<BigDecimal> highs, List<BigDecimal> lows,
                                                                List<BigDecimal> closes, List<BigDecimal> vols,
                                                                BigDecimal volAvg) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (k <= 0 || n <= 0) {
            return out;
        }
        k = Math.min(k, n);
        for (int s = 0; s < k; s++) {
            int i0 = (s * n) / k;
            int i1 = ((s + 1) * n) / k;
            Map<String, Object> seg = segmentStats(i0, i1, opens, highs, lows, closes, vols, volAvg);
            seg.put("seg_index", s);
            out.add(seg);
        }
        return out;
    }

    /**
     * 按 swing 拐点切段，头尾补齐到 [0, n-1]。
     * <p>
     * <b>相邻段共享边界那一根</b>：段是 [a, b+1)，语义是"从拐点 a 走到拐点 b"，两端都要算进去。
     * 代价是各段 volume_sum 之和会大于全段 total（实测 192 根这份数据多 13%）——
     * 这不是算错，是重叠。工具描述里得跟模型讲明白，否则它会拿去做加减法。
     */
    private static List<Map<String, Object>> buildSwingSegments(List<Swing> swings, int n,
                                                                List<BigDecimal> opens, List<BigDecimal> highs,
                                                                List<BigDecimal> lows, List<BigDecimal> closes,
                                                                List<BigDecimal> vols, BigDecimal volAvg) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (swings.isEmpty()) {
            return out;
        }
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(0);
        swings.forEach(s -> cuts.add(s.idx()));
        cuts.add(n - 1);
        if (cuts.size() < 2) {
            return out;
        }
        Map<Integer, List<Swing>> byIdx = new LinkedHashMap<>();
        swings.forEach(s -> byIdx.computeIfAbsent(s.idx(), k -> new ArrayList<>()).add(s));

        List<Integer> ordered = new ArrayList<>(cuts);
        for (int i = 0; i + 1 < ordered.size(); i++) {
            int a = ordered.get(i), b = ordered.get(i + 1);
            Map<String, Object> seg = segmentStats(a, b + 1, opens, highs, lows, closes, vols, volAvg);
            seg.put("from_swing", swingRef(a, byIdx.get(a)));
            seg.put("to_swing", swingRef(b, byIdx.get(b)));
            out.add(seg);
        }
        return out;
    }

    private static Map<String, Object> swingRef(int idx, List<Swing> at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idx", idx);
        if (at != null && !at.isEmpty()) {
            m.put("types", at.stream().map(Swing::type).toList());
            m.put("price", price(at.getFirst().price()));
        }
        return m;
    }

    // ==================== volume ====================

    private static Map<String, Object> buildVolume(List<BigDecimal> opens, List<BigDecimal> highs,
                                                   List<BigDecimal> lows, List<BigDecimal> closes,
                                                   List<BigDecimal> vols, long[] times, int n, Params p,
                                                   BigDecimal volTotal, BigDecimal volAvg) {
        int maxIdx = extremeIdx(vols, true);
        int ln = Math.min(p.lastN(), n);
        BigDecimal lastAvg = ln > 0
                ? divide(sum(vols, n - ln, n), BigDecimal.valueOf(ln)) : BigDecimal.ZERO;

        // 量降序取 top N；同量按 idx 升序，保证结果可复现
        List<Integer> topIdx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            topIdx.add(i);
        }
        topIdx.sort(Comparator.<Integer, BigDecimal>comparing(vols::get).reversed()
                .thenComparing(Comparator.naturalOrder()));
        List<Map<String, Object>> topBars = new ArrayList<>();
        for (int i : topIdx.subList(0, Math.min(Math.max(0, p.topVolumeBars()), n))) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("idx", i);
            b.put("time_ms", times[i]);
            b.put("volume", volume(vols.get(i)));
            b.put("open", price(opens.get(i)));
            b.put("high", price(highs.get(i)));
            b.put("low", price(lows.get(i)));
            b.put("close", price(closes.get(i)));
            b.put("body", price(closes.get(i).subtract(opens.get(i))));
            topBars.add(b);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", volume(volTotal));
        m.put("avg", volume(volAvg));
        m.put("max", volume(vols.get(maxIdx)));
        m.put("max_idx", maxIdx);
        m.put("max_time_ms", times[maxIdx]);
        m.put("last_n", ln);
        m.put("last_n_avg", volume(lastAvg));
        m.put("last_n_ratio", volAvg.signum() > 0
                ? divide(lastAvg, volAvg).setScale(RATIO_SCALE, RoundingMode.HALF_UP) : null);
        m.put("top_bars", topBars);
        return m;
    }

    // ==================== volatility ====================

    /**
     * 波动。两组数不是一回事，别混：
     * <ul>
     *   <li>{@code atr14} = Wilder RMA，与 indicators 工具同源同值，是"标准波动尺"（止损距离常拿它当单位）</li>
     *   <li>{@code avg_tr_*} = TR 的算术平均，记忆短、贴当下，用来看波动在收缩还是扩张</li>
     * </ul>
     * TR 序列统一从第 1 根起（第 0 根没有前收，算不出真实 TR），与 CryptoIndicatorCalculator 一致——
     * 两组数共用同一条 TR 序列，内部才不会又双轨。
     */
    private static Map<String, Object> buildVolatility(List<BigDecimal> highs, List<BigDecimal> lows,
                                                       List<BigDecimal> closes, int n, Params p,
                                                       List<String> warnings) {
        List<BigDecimal> trs = trueRanges(highs, lows, closes);
        BigDecimal atr = CryptoIndicatorCalculator.atr(highs, lows, closes, p.atrPeriod());
        if (atr == null) {
            warnings.add("atr_insufficient:period=" + p.atrPeriod());
        }
        BigDecimal cN = closes.get(n - 1);
        int trN = trs.size();
        int atrP = Math.min(p.atrPeriod(), trN);
        int ln = Math.min(p.lastN(), trN);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("atr_period", p.atrPeriod());
        m.put("atr14", atr == null ? null : price(atr));
        m.put("atr14_pct", atr == null ? null : pct(atr, cN));
        m.put("avg_tr_14", atrP > 0
                ? price(divide(sum(trs, trN - atrP, trN), BigDecimal.valueOf(atrP))) : null);
        m.put("avg_tr_all", trN > 0
                ? price(divide(sum(trs, 0, trN), BigDecimal.valueOf(trN))) : null);
        m.put("last_n", ln);
        BigDecimal lastTr = ln > 0 ? divide(sum(trs, trN - ln, trN), BigDecimal.valueOf(ln)) : null;
        BigDecimal allTr = trN > 0 ? divide(sum(trs, 0, trN), BigDecimal.valueOf(trN)) : null;
        m.put("avg_tr_last_n", lastTr == null ? null : price(lastTr));
        m.put("avg_tr_last_n_ratio", lastTr != null && allTr != null && allTr.signum() > 0
                ? divide(lastTr, allTr).setScale(RATIO_SCALE, RoundingMode.HALF_UP) : null);
        return m;
    }

    /** TR 序列，从第 1 根起（第 0 根无前收）。与 CryptoIndicatorCalculator.atr 的口径一致。 */
    private static List<BigDecimal> trueRanges(List<BigDecimal> highs, List<BigDecimal> lows,
                                               List<BigDecimal> closes) {
        List<BigDecimal> trs = new ArrayList<>();
        for (int i = 1; i < highs.size(); i++) {
            BigDecimal hl = highs.get(i).subtract(lows.get(i)).abs();
            BigDecimal hc = highs.get(i).subtract(closes.get(i - 1)).abs();
            BigDecimal lc = lows.get(i).subtract(closes.get(i - 1)).abs();
            trs.add(hl.max(hc).max(lc));
        }
        return trs;
    }

    // ==================== ma_context ====================

    private static Map<String, Object> buildMaContext(List<BigDecimal> closes, Params p, List<String> warnings) {
        BigDecimal cN = closes.getLast();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("close", price(cN));
        List<BigDecimal> got = new ArrayList<>();
        for (int period : p.maPeriods()) {
            BigDecimal mv = CryptoIndicatorCalculator.ma(closes, period);
            String key = "ma" + period;
            m.put(key, mv == null ? null : price(mv));
            if (mv == null) {
                m.put("close_vs_" + key, null);
                m.put("close_vs_" + key + "_pct", null);
                warnings.add("ma_insufficient:period=" + period);
            } else {
                m.put("close_vs_" + key, price(cN.subtract(mv)));
                m.put("close_vs_" + key + "_pct", pct(cN.subtract(mv), mv));
                got.add(mv);
            }
        }
        // 位置描述，不是信号：收盘价在全部均线之上/之下/夹在中间
        if (got.size() == p.maPeriods().size() && !got.isEmpty()) {
            boolean above = got.stream().allMatch(v -> cN.compareTo(v) > 0);
            boolean below = got.stream().allMatch(v -> cN.compareTo(v) < 0);
            m.put("stacked", above ? "above_all" : below ? "below_all" : "mixed");
        } else {
            m.put("stacked", null);
        }
        return m;
    }

    // ==================== levels ====================

    private static Map<String, Object> buildLevels(List<BigDecimal> highs, List<BigDecimal> lows,
                                                   List<BigDecimal> vols, List<Swing> swings, Params p) {
        List<BigDecimal> recentH = swings.stream().filter(s -> "H".equals(s.type()))
                .map(Swing::price).map(KlineStructureCalculator::price).toList();
        List<BigDecimal> recentL = swings.stream().filter(s -> "L".equals(s.type()))
                .map(Swing::price).map(KlineStructureCalculator::price).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        // 只回 top N 箱，总共分了几箱从结果里看不出来——它决定每箱多宽，得给
        m.put("vol_bin_count", p.volBinCount());
        m.put("vol_bins", volBins(highs, lows, vols, p.volBinCount(), p.volBinTop()));
        m.put("recent_swing_highs", tail(recentH, p.recentSwingLevels()));
        m.put("recent_swing_lows", tail(recentL, p.recentSwingLevels()));
        return m;
    }

    /**
     * 价位量能分箱：整根的量记在 (high+low)/2 落进的那一箱。
     * 粗近似——大振幅根的量本该摊到多个价位，这里没摊。够用于"换手集中在哪一带"。
     */
    private static List<Map<String, Object>> volBins(List<BigDecimal> highs, List<BigDecimal> lows,
                                                     List<BigDecimal> vols, int binCount, int top) {
        if (binCount <= 0 || vols.isEmpty()) {
            return List.of();
        }
        BigDecimal lo = lows.getFirst(), hi = highs.getFirst();
        for (int i = 1; i < vols.size(); i++) {
            lo = lo.min(lows.get(i));
            hi = hi.max(highs.get(i));
        }
        if (hi.compareTo(lo) <= 0) {
            Map<String, Object> only = new LinkedHashMap<>();
            only.put("bin_lo", price(lo));
            only.put("bin_hi", price(hi));
            only.put("volume", volume(sum(vols, 0, vols.size())));
            only.put("share_pct", HUNDRED.setScale(RATIO_SCALE, RoundingMode.HALF_UP));
            return List.of(only);
        }
        BigDecimal width = divide(hi.subtract(lo), BigDecimal.valueOf(binCount));
        BigDecimal[] buckets = new BigDecimal[binCount];
        java.util.Arrays.fill(buckets, BigDecimal.ZERO);
        for (int i = 0; i < vols.size(); i++) {
            BigDecimal mid = highs.get(i).add(lows.get(i)).divide(BigDecimal.TWO, CALC_SCALE, RoundingMode.HALF_UP);
            int bi = mid.subtract(lo).divide(width, 0, RoundingMode.FLOOR).intValue();
            bi = Math.clamp(bi, 0, binCount - 1);
            buckets[bi] = buckets[bi].add(vols.get(i));
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal b : buckets) {
            total = total.add(b);
        }
        if (total.signum() == 0) {
            total = BigDecimal.ONE;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < binCount; i++) {
            if (buckets[i].signum() <= 0) {
                continue;
            }
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("bin_lo", price(lo.add(width.multiply(BigDecimal.valueOf(i)))));
            it.put("bin_hi", price(lo.add(width.multiply(BigDecimal.valueOf(i + 1)))));
            it.put("volume", volume(buckets[i]));
            it.put("share_pct", pct(buckets[i], total));
            items.add(it);
        }
        // 量降序；同量保持箱位升序（Java sort 稳定），结果可复现
        items.sort(Comparator.comparing((Map<String, Object> x) -> (BigDecimal) x.get("volume")).reversed());
        return items.subList(0, Math.min(Math.max(0, top), items.size()));
    }

    // ==================== focus_bars ====================

    /** 给模型的证据 K 线：最近一段 + 三个关键点附近，避免整段 192 根都塞进去。 */
    private static Map<String, Object> buildFocusBars(List<KlineBar> bars, List<BigDecimal> highs,
                                                      List<BigDecimal> lows, List<BigDecimal> vols,
                                                      int n, Params p) {
        int ln = Math.min(p.lastN(), n);
        List<Map<String, Object>> last = new ArrayList<>();
        for (int i = Math.max(0, n - ln); i < n; i++) {
            last.add(barMap(i, bars.get(i)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("last", last);
        m.put("around_high", sliceAround(bars, extremeIdx(highs, true), p.focusRadius()));
        m.put("around_low", sliceAround(bars, extremeIdx(lows, false), p.focusRadius()));
        m.put("around_max_volume", sliceAround(bars, extremeIdx(vols, true), p.focusRadius()));
        return m;
    }

    private static List<Map<String, Object>> sliceAround(List<KlineBar> bars, int center, int radius) {
        int i0 = Math.max(0, center - radius);
        int i1 = Math.min(bars.size(), center + radius + 1);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = i0; i < i1; i++) {
            out.add(barMap(i, bars.get(i)));
        }
        return out;
    }

    private static Map<String, Object> barMap(int idx, KlineBar b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idx", idx);
        m.put("t", b.openTime());
        m.put("o", price(b.open()));
        m.put("h", price(b.high()));
        m.put("l", price(b.low()));
        m.put("c", price(b.close()));
        m.put("v", volume(b.volume()));
        return m;
    }

    // ==================== 基础工具 ====================

    /** 极值下标，并列取最先出现的那根（与 py 的 list.index 语义一致）。 */
    private static int extremeIdx(List<BigDecimal> values, boolean max) {
        int idx = 0;
        for (int i = 1; i < values.size(); i++) {
            int cmp = values.get(i).compareTo(values.get(idx));
            if (max ? cmp > 0 : cmp < 0) {
                idx = i;
            }
        }
        return idx;
    }

    private static BigDecimal sum(List<BigDecimal> values, int from, int to) {
        BigDecimal s = BigDecimal.ZERO;
        for (int i = from; i < to; i++) {
            s = s.add(values.get(i));
        }
        return s;
    }

    private static BigDecimal divide(BigDecimal a, BigDecimal b) {
        return b.signum() == 0 ? BigDecimal.ZERO : a.divide(b, CALC_SCALE, RoundingMode.HALF_UP);
    }

    private static <T> List<T> tail(List<T> list, int n) {
        return n <= 0 || list.size() <= n ? list : list.subList(list.size() - n, list.size());
    }

    // 价格与量当前用同一套精度，分开命名只为在调用处标出字段语义
    private static BigDecimal price(BigDecimal v) {
        return v == null ? null : trim(v.round(OUT_MC));
    }

    private static BigDecimal volume(BigDecimal v) {
        return v == null ? null : trim(v.round(OUT_MC));
    }

    /** num/den*100，den=0 给 null（由调用方按"算不出"处理，不要塞 0 冒充） */
    private static BigDecimal pct(BigDecimal num, BigDecimal den) {
        if (num == null || den == null || den.signum() == 0) {
            return null;
        }
        return trim(num.multiply(HUNDRED).divide(den, RATIO_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 砍掉补位的尾随零：65183.9000 与 65183.9 数值相同，多出来的零纯占 token。
     * stripTrailingZeros 遇到整数会甩出科学计数法（1E+2），过一遍 toPlainString 拉回普通标度。
     */
    private static BigDecimal trim(BigDecimal v) {
        return new BigDecimal(v.stripTrailingZeros().toPlainString());
    }
}
