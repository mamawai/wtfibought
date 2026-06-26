package com.mawai.wiibquant.agent.quant.domain.briefing;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibquant.agent.quant.domain.debate.WeakLean;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragileDirection;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityLevel;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityScore;
import com.mawai.wiibquant.agent.quant.domain.signal.Signal;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalGroup;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalLean;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BriefingSnapshotTest {

    @Test
    void serializesToTransferableJsonForEndpoint() {
        // endpoint 把 briefing_json 透传为 JSONObject 给前端，验证序列化后字段结构完整
        FragilityScore fragility = new FragilityScore(62, FragilityLevel.HIGH, 0.66, 0.3, 0.5,
                FragileDirection.DOWN, "多头拥挤 → 下行脆弱（脆弱度 脆弱 62）");
        SignalPanel panel = new SignalPanel(List.of(
                new Signal("HIGH_FUNDING", "资金费率偏高", SignalLean.BEARISH, SignalGroup.POSITIONING, "microstructure"),
                new Signal("NEWS", "ETF inflow", SignalLean.BULLISH, SignalGroup.NEWS, "news_event", "影响high")));
        List<WeakLean> leans = List.of(
                WeakLean.from("H6", "SHORT", 20, 30, 50, "若拥挤兑现或回落", "funding回正则作废"));

        String json = JSON.toJSONString(BriefingSnapshot.of(fragility, panel, leans));
        JSONObject obj = JSON.parseObject(json); // 同 endpoint 透传路径（无 typed 反序列化）

        assertThat(obj.getJSONObject("fragility").getIntValue("score")).isEqualTo(62);
        assertThat(obj.getJSONObject("fragility").getString("level")).isEqualTo("HIGH");
        assertThat(obj.getJSONObject("fragility").getString("direction")).isEqualTo("DOWN");

        JSONArray signals = obj.getJSONObject("signalPanel").getJSONArray("signals");
        assertThat(signals).hasSize(2);
        assertThat(signals.getJSONObject(0).getString("code")).isEqualTo("HIGH_FUNDING");
        assertThat(signals.getJSONObject(0).getString("lean")).isEqualTo("BEARISH");
        assertThat(signals.getJSONObject(0).getString("group")).isEqualTo("POSITIONING");

        JSONArray wl = obj.getJSONArray("weakLeans");
        assertThat(wl).hasSize(1);
        assertThat(wl.getJSONObject(0).getString("lean")).isEqualTo("SHORT");
        assertThat(wl.getJSONObject(0).getString("invalidation")).contains("作废");
    }

    @Test
    void ofNullsBecomeSafeDefaults() {
        BriefingSnapshot b = BriefingSnapshot.of(null, null, null);

        assertThat(b.fragility()).isNull();
        assertThat(b.signalPanel().signals()).isEmpty();
        assertThat(b.weakLeans()).isEmpty();
    }
}
