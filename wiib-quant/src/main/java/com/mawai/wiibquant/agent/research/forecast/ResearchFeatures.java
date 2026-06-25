package com.mawai.wiibquant.agent.research.forecast;

import com.mawai.wiibquant.agent.research.factor.ContinuousFactorVector;
import com.mawai.wiibcommon.market.KlineBar;

import java.util.List;

/**
 * 升级后的 {@link Forecaster} 入参：决策点"当下"的 point-in-time 上下文。
 * barsUpToNow 绝不含未来；fundingRate/fearGreed/etfFlow/stablecoinDelta 由 SeriesAligner as-of 对齐而来
 * （无数据则中性：funding=0、fng=50、etf=0、stablecoin=0）。
 * continuousFactors 是同一决策点的结构化连续因子向量；纯价格预测器（如 EWMA 基线）可忽略额外字段。
 */
public record ResearchFeatures(List<KlineBar> barsUpToNow, double fundingRate, int fearGreed,
                               double etfFlow, double stablecoinDelta,
                               ContinuousFactorVector continuousFactors) {

    public ResearchFeatures(List<KlineBar> barsUpToNow, double fundingRate, int fearGreed,
                            double etfFlow, double stablecoinDelta) {
        this(barsUpToNow, fundingRate, fearGreed, etfFlow, stablecoinDelta, ContinuousFactorVector.neutral());
    }

    public ResearchFeatures {
        if (continuousFactors == null) {
            continuousFactors = ContinuousFactorVector.neutral();
        }
    }

    /** 纯价格场景便捷构造：链下/链上全取中性默认（基线、单测用）。 */
    public static ResearchFeatures ofBars(List<KlineBar> barsUpToNow) {
        return new ResearchFeatures(barsUpToNow, 0.0, 50, 0.0, 0.0);
    }

    /** 三因子(趋势+资金费+恐惧贪婪)场景便捷构造：链上 ETF/稳定币取中性 0。 */
    public static ResearchFeatures of(List<KlineBar> barsUpToNow, double fundingRate, int fearGreed) {
        return new ResearchFeatures(barsUpToNow, fundingRate, fearGreed, 0.0, 0.0);
    }

    /** 由最近两根 bar 的 openTime 推断特征周期；测试桩时间缺失时返回 0，由调用方按“不缩放”处理。 */
    public long inferredBarMillis() {
        if (barsUpToNow == null || barsUpToNow.size() < 2) return 0L;
        KlineBar last = barsUpToNow.get(barsUpToNow.size() - 1);
        KlineBar prev = barsUpToNow.get(barsUpToNow.size() - 2);
        long interval = last.openTime() - prev.openTime();
        return interval > 0 ? interval : 0L;
    }
}
