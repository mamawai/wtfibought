package com.mawai.wiibquant.research.forecast;

import com.mawai.wiibquant.research.factor.ContinuousFactorVector;
import com.mawai.wiibcommon.market.KlineBar;

import java.util.List;

/**
 * {@link Forecaster} 入参：决策点"当下"的 point-in-time 上下文。
 * barsUpToNow 绝不含未来；continuousFactors 是同一决策点的结构化连续因子向量，纯价格预测器（如 EWMA 基线）可忽略。
 */
public record ResearchFeatures(List<KlineBar> barsUpToNow, ContinuousFactorVector continuousFactors) {

    public ResearchFeatures(List<KlineBar> barsUpToNow) {
        this(barsUpToNow, ContinuousFactorVector.neutral());
    }

    public ResearchFeatures {
        if (continuousFactors == null) {
            continuousFactors = ContinuousFactorVector.neutral();
        }
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
