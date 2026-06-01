package com.mawai.wiibservice.agent.research.series;

import java.math.BigDecimal;
import java.util.List;

/**
 * point-in-time as-of join（纯函数，无状态）：给定决策点 ts，取链下序列里 ts ≤ 决策点的最近一个值。
 * 整把尺子"无未来泄漏"的正确性核心——只让决策点看到"当下已发布"的链下数据，绝不取未来。
 */
public final class SeriesAligner {

    private SeriesAligner() {
    }

    /**
     * as-of：序列中 ts ≤ asOfTs 的最近值（floor）。序列须按 ts 升序（{@code MarketSeriesStore.load} 的契约）。
     * ts == asOfTs 算"已发布/已知"（合法，非泄漏）；无满足点（序列空 / asOfTs 早于首点）→ neutralDefault。
     */
    public static BigDecimal asOf(List<MarketSeriesPoint> series, long asOfTs, BigDecimal neutralDefault) {
        if (series == null || series.isEmpty()) return neutralDefault;
        // 二分找最大的 ts ≤ asOfTs（floor）；序列升序，绝不返回 ts > asOfTs 的未来值
        int lo = 0, hi = series.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (series.get(mid).ts() <= asOfTs) {
                ans = mid;          // 候选，继续往右找更接近决策点的
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans < 0 ? neutralDefault : series.get(ans).value();
    }
}
