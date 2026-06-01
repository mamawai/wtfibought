package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.kline.KlineBar;

import java.util.List;

/**
 * 升级后的 {@link Forecaster} 入参：决策点"当下"的 point-in-time 上下文。
 * barsUpToNow 绝不含未来；fundingRate/fearGreed 由 SeriesAligner as-of 对齐而来（无数据则中性：funding=0、fng=50）。
 * 纯价格预测器（如 EWMA 基线）只用 barsUpToNow，忽略链下字段。
 */
public record ResearchFeatures(List<KlineBar> barsUpToNow, double fundingRate, int fearGreed) {

    /** 纯价格场景便捷构造：链下取中性默认（基线、单测、T6 前的中间态用）。 */
    public static ResearchFeatures ofBars(List<KlineBar> barsUpToNow) {
        return new ResearchFeatures(barsUpToNow, 0.0, 50);
    }
}
