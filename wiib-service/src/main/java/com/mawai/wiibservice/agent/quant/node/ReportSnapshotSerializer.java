package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;

final class ReportSnapshotSerializer {

    String serialize(FeatureSnapshot snapshot, OverAllState state) {
        JSONObject obj = JSON.parseObject(JSON.toJSONString(snapshot));
        // B1-4 方差值是工作流 state 指标；落入 snapshot_json 后 C3 看板才能跨重启/跨页面查询。
        state.value("regime_confidence_stddev").ifPresent(v -> obj.put("regimeConfidenceStddev", v));
        state.value("news_confidence_stddev").ifPresent(v -> obj.put("newsConfidenceStddev", v));
        state.value("news_low_confidence").ifPresent(v -> obj.put("newsLowConfidence", v));
        state.value("memory_weight_adjustments").ifPresent(v -> obj.put("memoryWeightAdjustments", v));
        state.value("macro_context").ifPresent(v -> obj.put("macroContext", v));
        return obj.toJSONString();
    }
}
