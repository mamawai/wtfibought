package com.mawai.wiibservice.agent.research.forecast;

import java.util.Arrays;

/**
 * 波动状态分类器：把一个 vol 值按"历史 vol 分布的三分位"分到 低/中/高 三档。
 *
 * <p>tercile 等频 → 三档天然均衡，分类技能(balanced accuracy)不会被"全押多数类"刷假
 * (这正是 ADX 4 类 regime 栽的坑)。该逻辑经 BTC/ETH/SOL × H6/12/24 样本外验证：
 * balanced accuracy 50-58%(vs 33.3% 随机)，确有技能，高波识别尤准。</p>
 *
 * <p>用法：vol forecaster 产出预测 vol → {@link #classify} 得预测状态；
 * 事后用实际已实现 vol → classify 得实际状态；两者一比即 vol-state 对账。
 * 档界由调用方喂入的历史保证 point-in-time(扩张窗/滚动窗)无泄漏。</p>
 */
public final class VolStateClassifier {

    private final double[] sortedAscending;   // 历史 vol 升序(防御性 copy)，供分位查询
    private final double lowCut;              // 1/3 分位：vol < 此 → 低波
    private final double highCut;             // 2/3 分位：vol > 此 → 高波

    private VolStateClassifier(double[] sortedAscending, double lowCut, double highCut) {
        this.sortedAscending = sortedAscending;
        this.lowCut = lowCut;
        this.highCut = highCut;
    }

    /** 用历史 vol 分布定三分位档界；空历史退化为"全部归 MID"。 */
    public static VolStateClassifier fromHistory(double[] vols) {
        if (vols == null || vols.length == 0) {
            return new VolStateClassifier(new double[0], 0.0, Double.MAX_VALUE);
        }
        double[] sorted = vols.clone();
        Arrays.sort(sorted);
        return new VolStateClassifier(sorted, quantile(sorted, 1.0 / 3.0), quantile(sorted, 2.0 / 3.0));
    }

    /** 界点归中档：vol==lowCut 或 vol==highCut 都算 MID。 */
    public VolState classify(double vol) {
        if (vol < lowCut) return VolState.LOW;
        if (vol > highCut) return VolState.HIGH;
        return VolState.MID;
    }

    /** vol 在历史分布的分位 [0,1] = 严格小于 vol 的历史占比；空历史返回 0.5。 */
    public double percentile(double vol) {
        int n = sortedAscending.length;
        if (n == 0) return 0.5;
        return (double) lowerBound(sortedAscending, vol) / n;
    }

    public double lowCut() {
        return lowCut;
    }

    public double highCut() {
        return highCut;
    }

    private static double quantile(double[] sorted, double q) {
        int idx = (int) Math.floor(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    /** 第一个 >= key 的下标，即严格小于 key 的元素个数。 */
    private static int lowerBound(double[] sorted, double key) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
