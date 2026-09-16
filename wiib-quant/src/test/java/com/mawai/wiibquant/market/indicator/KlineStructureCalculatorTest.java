package com.mawai.wiibquant.market.indicator;

import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KlineStructureCalculatorTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long STEP = 300_000L;

    private static KlineBar bar(int idx, double open, double high, double low, double close, double vol) {
        long t = T0 + idx * STEP;
        return new KlineBar(t, t + STEP - 1,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.valueOf(vol));
    }

    /**
     * 手工锯齿：idx=3 是唯一的局部高点，idx=7 是唯一的局部低点（window=3 时）。
     * 每根量固定 10，好验分段重叠。
     */
    private static List<KlineBar> zigzag() {
        double[] highs = {100, 102, 104, 106, 104, 102, 100, 98, 100, 102, 104, 106, 104};
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < highs.length; i++) {
            bars.add(bar(i, highs[i] - 1, highs[i], highs[i] - 1, highs[i] - 0.5, 10));
        }
        return bars;
    }

    /** 平缓上行的长序列，够算 ATR/MA/ADX。 */
    private static List<KlineBar> ramp(int n) {
        List<KlineBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double base = 100 + Math.sin(i / 7.0) * 5 + i * 0.05;
            bars.add(bar(i, base, base + 1.2, base - 1.1, base + 0.3, 10 + (i % 13)));
        }
        return bars;
    }

    private static Map<String, Object> compute(List<KlineBar> bars) {
        return KlineStructureCalculator.compute(bars, KlineStructureCalculator.Params.defaults());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> block(Map<String, Object> out, String key) {
        return (Map<String, Object>) out.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> out, String key) {
        return (List<Map<String, Object>>) out.get(key);
    }

    // ==================== 输入校验 ====================

    @Test
    void nullAndEmptyBarsReportErrors() {
        assertThat((List<?>) KlineStructureCalculator.compute(null,
                KlineStructureCalculator.Params.defaults()).get("errors")).isNotEmpty();
        assertThat((List<?>) KlineStructureCalculator.compute(List.of(),
                KlineStructureCalculator.Params.defaults()).get("errors")).isNotEmpty();
    }

    @Test
    void highBelowLowIsHardError() {
        List<KlineBar> bad = new ArrayList<>(ramp(40));
        bad.set(5, bar(5, 100, 90, 95, 96, 10));    // high < low

        assertThat((List<?>) compute(bad).get("errors")).isNotEmpty();
    }

    @Test
    void inconsistentOhlcIsWarningNotError() {
        // high 没罩住实体：交易所偶发脏数据，不该让整份摘要作废，但得让模型知道
        List<KlineBar> dirty = new ArrayList<>(ramp(40));
        dirty.set(5, bar(5, 100, 100.5, 99, 103, 10));   // close 高过 high

        Map<String, Object> out = compute(dirty);

        assertThat((List<?>) out.get("errors")).isEmpty();
        assertThat((List<String>) out.get("warnings"))
                .anyMatch(w -> w.startsWith("ohlc_inconsistent_bars="));
    }

    // ==================== swings ====================

    @Test
    void swingsFindLocalExtremesOnly() {
        List<Map<String, Object>> swings = list(compute(zigzag()), "swings");

        // window=6 时窗口不完整（13 根 < 6*2+1），换成手动指定的 3
        Map<String, Object> out = KlineStructureCalculator.compute(zigzag(),
                new KlineStructureCalculator.Params(3, 3, 5, 2, 3, List.of(20, 60), 14, 8, 4, 5, true));
        List<Map<String, Object>> s3 = list(out, "swings");

        assertThat(swings).isEmpty();          // 半窗 6 装不下 13 根，一个都出不来
        assertThat(s3).hasSize(2);
        assertThat(s3.getFirst()).containsEntry("idx", 3).containsEntry("type", "H");
        assertThat(s3.get(1)).containsEntry("idx", 7).containsEntry("type", "L");
    }

    @Test
    void lastWindowBarsCanNeverBeSwings() {
        // 右侧没走完就不算确认，这是防重绘的固有代价
        Map<String, Object> out = KlineStructureCalculator.compute(ramp(60),
                new KlineStructureCalculator.Params(6, 3, 20, 3, 8, List.of(20, 60), 14, 24, 8, 5, true));

        assertThat(list(out, "swings")).allSatisfy(s ->
                assertThat((Integer) s.get("idx")).isLessThan(60 - 6));
    }

    // ==================== segments ====================

    @Test
    void swingSegmentsShareBoundaryBarSoVolumesOverlap() {
        // 段是 [a, b] 含两端，相邻段共用拐点那一根，所以各段量之和必然大于总量
        Map<String, Object> out = KlineStructureCalculator.compute(zigzag(),
                new KlineStructureCalculator.Params(3, 3, 5, 2, 3, List.of(20, 60), 14, 8, 4, 5, true));
        List<Map<String, Object>> segs = list(out, "segments_swing");

        // cuts = [0, 3, 7, 12] → 三段，各含 4 / 5 / 6 根
        assertThat(segs).hasSize(3);
        assertThat(segs.get(0)).containsEntry("i0", 0).containsEntry("i1", 4);
        assertThat(segs.get(1)).containsEntry("i0", 3).containsEntry("i1", 8);
        assertThat(segs.get(2)).containsEntry("i0", 7).containsEntry("i1", 13);

        BigDecimal segSum = segs.stream().map(s -> (BigDecimal) s.get("volume_sum"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = (BigDecimal) block(out, "volume").get("total");
        assertThat(segSum).isEqualByComparingTo("150");   // 4+5+6 根 × 10
        assertThat(total).isEqualByComparingTo("130");    // 13 根 × 10
        assertThat(segSum).isGreaterThan(total);
    }

    @Test
    void equalSegmentsCoverEverythingWithoutOverlap() {
        List<Map<String, Object>> segs = list(compute(ramp(90)), "segments_equal");

        assertThat(segs).hasSize(3);
        assertThat(segs.get(0)).containsEntry("i0", 0).containsEntry("i1", 30);
        assertThat(segs.get(1)).containsEntry("i0", 30).containsEntry("i1", 60);
        assertThat(segs.get(2)).containsEntry("i0", 60).containsEntry("i1", 90);
    }

    // ==================== volatility ====================

    @Test
    void atr14ComesFromTheSharedWilderImplementation() {
        // 与 indicators 工具同源同值的根本保证：这里不自己算 ATR
        List<KlineBar> bars = ramp(150);
        List<BigDecimal> h = bars.stream().map(KlineBar::high).toList();
        List<BigDecimal> l = bars.stream().map(KlineBar::low).toList();
        List<BigDecimal> c = bars.stream().map(KlineBar::close).toList();

        BigDecimal fromStructure = (BigDecimal) block(compute(bars), "volatility").get("atr14");

        assertThat(fromStructure).isEqualByComparingTo(CryptoIndicatorCalculator.atr(h, l, c, 14));
    }

    @Test
    void avgTrIsArithmeticMeanAndDiffersFromWilderAtr() {
        // 两个字段是两回事：avg_tr_14 记忆短、贴当下，atr14 是 Wilder。同名会害死模型，所以分开命名
        Map<String, Object> v = block(compute(ramp(150)), "volatility");

        assertThat(v.get("atr14")).isNotNull();
        assertThat(v.get("avg_tr_14")).isNotNull();
        assertThat(v).containsKeys("avg_tr_all", "avg_tr_last_n", "avg_tr_last_n_ratio");
        assertThat(v).doesNotContainKey("atr");           // 裸 atr 不许出现，避免与 atr14 混淆
    }

    // ==================== 精度 ====================

    @Test
    void lowPricedSymbolsKeepSignificantDigits() {
        // DOGE 量级：固定 4 位小数会把 0.10234 切成 0.1023，有效数字直接少一位
        List<KlineBar> cheap = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double base = 0.10234 + i * 0.00001;
            cheap.add(bar(i, base, base + 0.00002, base - 0.00002, base + 0.00001, 1000));
        }

        BigDecimal open = (BigDecimal) block(compute(cheap), "range").get("open");

        assertThat(open).isEqualByComparingTo("0.10234");
    }

    @Test
    void trailingZerosAreStripped() {
        List<KlineBar> bars = ramp(40);
        BigDecimal high = (BigDecimal) block(compute(bars), "range").get("high");

        // 要的是最短表示：105.4 而不是 105.4000
        assertThat(high.toPlainString()).doesNotEndWith("0");
    }

    // ==================== levels / focus ====================

    @Test
    void volBinsReportTotalCountBecauseOnlyTopNAreReturned() {
        Map<String, Object> levels = block(compute(ramp(90)), "levels");

        assertThat(levels).containsEntry("vol_bin_count", 24);
        assertThat(list(levels, "vol_bins")).hasSizeLessThanOrEqualTo(8);
    }

    @Test
    void focusBarsCoverRecentAndEachKeyPoint() {
        Map<String, Object> out = compute(ramp(90));
        Map<String, Object> focus = block(out, "focus_bars");
        int highIdx = (int) block(out, "range").get("high_idx");

        assertThat(focus).containsKeys("last", "around_high", "around_low", "around_max_volume");
        assertThat(list(focus, "last")).hasSize(20);
        assertThat(list(focus, "around_high")).anySatisfy(b ->
                assertThat(b).containsEntry("idx", highIdx));
    }

    @Test
    void metaKeepsOnlyWhatCannotBeInferred() {
        Map<String, Object> meta = block(compute(ramp(90)), "meta");

        assertThat(meta).containsKeys("bar_count", "start_time_ms", "end_time_ms",
                "duration_ms", "last_forming", "swing_window");
        assertThat(meta).doesNotContainKey("params");     // 参数回显已删，能从输出自行推断
    }

    @Test
    void noJudgementWordsLeakIntoOutput() {
        // 中性事实层的底线：不替 trader 定派系
        String json = com.mawai.wiibcommon.util.JsonUtils.MAPPER.writeValueAsString(compute(ramp(90)));

        assertThat(json).doesNotContainIgnoringCase("bullish")
                .doesNotContainIgnoringCase("bearish")
                .doesNotContainIgnoringCase("reversal")
                .doesNotContainIgnoringCase("breakout")
                .doesNotContainIgnoringCase("squeeze");
    }
}
