package com.mawai.wiibquant.research.forecast;

import com.mawai.wiibcommon.market.KlineBar;

import java.util.List;

/** 测试造数：ResearchFeatures 的便捷工厂，连续因子取中性默认。生产一律全参构造（见 ResearchEvalService）。 */
final class TestFeatures {

    private TestFeatures() {
    }

    /** 纯价格场景：EWMA/HAR-RV 等只看 K 线的预测器 */
    static ResearchFeatures ofBars(List<KlineBar> barsUpToNow) {
        return new ResearchFeatures(barsUpToNow);
    }
}
