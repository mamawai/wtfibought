package com.mawai.wiibservice.agent.research.benchmark;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Random;
import java.util.function.IntConsumer;

/** 双基准：buy&hold 净收益 + 随机入场（排列检验）分位。 */
public final class BenchmarkCalculator {

    private BenchmarkCalculator() {
    }

    /** buy&hold = 末根 close / 首根 close − 1。 */
    public static BigDecimal buyAndHoldReturn(List<KlineBar> bars) {
        if (bars == null || bars.size() < 2) return BigDecimal.ZERO;
        BigDecimal first = bars.get(0).close();
        BigDecimal last = bars.get(bars.size() - 1).close();
        if (first.signum() == 0) return BigDecimal.ZERO;
        return last.subtract(first).divide(first, 10, RoundingMode.HALF_UP);
    }

    /**
     * Aronson 排列检验 [S11]：固定真实仓位序列 → realReturn = Σ pos_i·ret_i；
     * 再把仓位序列洗牌 iterations 次（保留下注次数/方向分布，打乱时机），
     * 统计有多少次随机收益 < realReturn → 即真实策略所处分位（同频同持仓的"瞎猜"分布）。
     * seed 固定 → 结果可复现（spec §6.2）。
     */
    public static double permutationPercentile(List<Integer> positions, List<BigDecimal> periodReturns,
                                               int iterations, long seed) {
        return permutationPercentile(positions, periodReturns, iterations, seed, null);
    }

    /** 同上；progress 回传已完成迭代数，给长窗口本地 runner 做进度输出。 */
    public static double permutationPercentile(List<Integer> positions, List<BigDecimal> periodReturns,
                                               int iterations, long seed, IntConsumer progress) {
        int n = positions.size();
        if (n == 0 || iterations <= 0) return 0.0;
        double[] rr = new double[n];
        int[] pos = new int[n];
        double real = 0;
        for (int i = 0; i < n; i++) {
            rr[i] = periodReturns.get(i).doubleValue();
            pos[i] = positions.get(i);
            real += pos[i] * rr[i];
        }
        Random rnd = new Random(seed);
        int lessCount = 0;
        for (int it = 0; it < iterations; it++) {
            int[] p = pos.clone();
            for (int i = n - 1; i > 0; i--) {        // Fisher-Yates 洗牌
                int j = rnd.nextInt(i + 1);
                int t = p[i];
                p[i] = p[j];
                p[j] = t;
            }
            double ret = 0;
            for (int i = 0; i < n; i++) ret += p[i] * rr[i];
            if (ret < real) lessCount++;
            if (progress != null) {
                progress.accept(it + 1);
            }
        }
        return (double) lessCount / iterations;
    }
}
