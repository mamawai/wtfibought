package com.mawai.wiibquant.agent.strategy.core;

import com.mawai.wiibquant.agent.research.kline.KlineAggregator;
import com.mawai.wiibcommon.market.KlineBar;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 滚动 5m 窗口 + 按需聚合的取数实现。回测与 live 共用本类（live 多传 liveData），
 * 聚合统一走 KlineAggregator，保证两边 swing/指标完全同源。
 * 每根5m全量重聚合 O(n)，n≈1.2万，纳秒级乘法可忽略，不做增量优化。
 */
public final class WindowedMarketView implements StrategyMarketView {

    private final ArrayList<KlineBar> base5m = new ArrayList<>();
    private final int maxBars;
    private String baseGapDescription;
    private int baseGapCount;

    public WindowedMarketView(int maxBars) {
        this.maxBars = maxBars;
    }

    /** 按 openTime 追加；乱序/重复直接忽略（live WS 可能重发）。 */
    public void append(KlineBar bar) {
        if (bar == null) return;
        if (!base5m.isEmpty() && bar.openTime() <= base5m.getLast().openTime()) return;
        String newGap = newBaseGap(base5m.isEmpty() ? null : base5m.getLast(), bar);
        base5m.add(bar);
        if (newGap != null) {
            baseGapCount++;
            if (baseGapDescription == null) baseGapDescription = newGap;
        }
        if (base5m.size() > maxBars) {
            base5m.subList(0, base5m.size() - maxBars).clear();
            if (baseGapCount > 0) refreshBaseGap();
        }
    }

    @Override
    public List<KlineBar> closedBars(long horizonMillis, int maxCount) {
        List<KlineBar> bars;
        if (horizonMillis == BASE_INTERVAL_MILLIS) {
            bars = base5m;
        } else {
            List<KlineBar> source = aggregationSource(horizonMillis, maxCount);
            List<KlineBar> agg = new ArrayList<>(KlineAggregator.aggregate(source, horizonMillis));
            // 末桶不完整则丢弃：closeTime 必须顶到桶右边界（Binance closeTime=openTime+interval-1ms）
            if (!agg.isEmpty()) {
                KlineBar last = agg.getLast();
                if (last.closeTime() < last.openTime() + horizonMillis - 1) {
                    agg.removeLast();
                }
            }
            bars = agg;
        }
        if (bars.size() <= maxCount) return List.copyOf(bars);
        return List.copyOf(bars.subList(bars.size() - maxCount, bars.size()));
    }

    @Override
    public boolean hasBaseGap() {
        return baseGapDescription != null;
    }

    @Override
    public Optional<String> baseGapDescription() {
        return Optional.ofNullable(baseGapDescription);
    }

    @Override
    public long nowMs() {
        return base5m.isEmpty() ? 0L : base5m.getLast().closeTime();
    }

    public static Optional<String> firstBaseGapDescription(List<KlineBar> bars) {
        return Optional.ofNullable(firstBaseGap(bars));
    }

    private List<KlineBar> aggregationSource(long horizonMillis, int maxCount) {
        if (maxCount <= 0 || horizonMillis <= BASE_INTERVAL_MILLIS
                || horizonMillis % BASE_INTERVAL_MILLIS != 0
                || maxCount == Integer.MAX_VALUE) {
            return base5m;
        }
        long barsPerBucketLong = horizonMillis / BASE_INTERVAL_MILLIS;
        if (barsPerBucketLong > Integer.MAX_VALUE / Math.max(1, maxCount + 2)) {
            return base5m;
        }
        int barsPerBucket = (int) barsPerBucketLong;
        int needed = Math.max(1, maxCount + 2) * barsPerBucket;
        if (base5m.size() <= needed) return base5m;
        return base5m.subList(base5m.size() - needed, base5m.size());
    }

    private void refreshBaseGap() {
        baseGapDescription = firstBaseGap(base5m);
        baseGapCount = baseGapDescription == null ? 0 : 1;
    }

    private static String firstBaseGap(List<KlineBar> bars) {
        if (bars == null || bars.isEmpty()) return null;
        for (int i = 0; i < bars.size(); i++) {
            String gap = newBaseGap(i > 0 ? bars.get(i - 1) : null, bars.get(i));
            if (gap != null) return gap;
        }
        return null;
    }

    private static String newBaseGap(KlineBar prev, KlineBar bar) {
        long expectedClose = bar.openTime() + BASE_INTERVAL_MILLIS - 1;
        if (bar.closeTime() != expectedClose) {
            return "5m K线时间不合法 openTime=" + bar.openTime()
                    + " closeTime=" + bar.closeTime()
                    + " expectedClose=" + expectedClose;
        }
        if (prev != null) {
            long expectedOpen = prev.openTime() + BASE_INTERVAL_MILLIS;
            if (bar.openTime() != expectedOpen) {
                return "5m K线缺口 prevOpenTime=" + prev.openTime()
                        + " expectedOpenTime=" + expectedOpen
                        + " actualOpenTime=" + bar.openTime();
            }
        }
        return null;
    }
}
