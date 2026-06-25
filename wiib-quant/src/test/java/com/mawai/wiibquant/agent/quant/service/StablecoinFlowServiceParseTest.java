package com.mawai.wiibquant.agent.quant.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StablecoinFlowServiceParseTest {

    @Test
    void parseSupplyDeltasComputesPairwiseDeltasAscending() {
        // 3 期供给 100→130→125，date 升序；产出 2 个相邻差：+30、-5
        String json = "["
                + "{\"date\":\"1700000000\",\"totalCirculatingUSD\":{\"peggedUSD\":100}},"
                + "{\"date\":\"1700086400\",\"totalCirculatingUSD\":{\"peggedUSD\":130}},"
                + "{\"date\":\"1700172800\",\"totalCirculatingUSD\":{\"peggedUSD\":125}}"
                + "]";

        List<StablecoinFlowService.StablecoinPoint> pts = StablecoinFlowService.parseSupplyDeltas(json);

        assertThat(pts).hasSize(2);
        assertThat(pts.get(0).dateSec()).isEqualTo(1700086400L);
        assertThat(pts.get(0).delta()).isEqualByComparingTo("30");
        assertThat(pts.get(0).previousDateSec()).isEqualTo(1700000000L);
        assertThat(pts.get(1).dateSec()).isEqualTo(1700172800L);
        assertThat(pts.get(1).delta()).isEqualByComparingTo("-5");
    }

    @Test
    void parseSupplyDeltasBridgesOverPointsMissingSupply() {
        // 中间一期缺 peggedUSD：不产生 delta，且不推进"上一期"→ 125 的差对照最近有效期 100
        String json = "["
                + "{\"date\":\"1700000000\",\"totalCirculatingUSD\":{\"peggedUSD\":100}},"
                + "{\"date\":\"1700086400\",\"totalCirculatingUSD\":{}},"
                + "{\"date\":\"1700172800\",\"totalCirculatingUSD\":{\"peggedUSD\":125}}"
                + "]";

        List<StablecoinFlowService.StablecoinPoint> pts = StablecoinFlowService.parseSupplyDeltas(json);

        assertThat(pts).hasSize(1);
        assertThat(pts.get(0).dateSec()).isEqualTo(1700172800L);
        assertThat(pts.get(0).delta()).isEqualByComparingTo("25");          // 125 - 100
        assertThat(pts.get(0).previousDateSec()).isEqualTo(1700000000L);
    }

    @Test
    void tooFewPointsGivesEmpty() {
        assertThat(StablecoinFlowService.parseSupplyDeltas(
                "[{\"date\":\"1\",\"totalCirculatingUSD\":{\"peggedUSD\":1}}]")).isEmpty();
        assertThat(StablecoinFlowService.parseSupplyDeltas("[]")).isEmpty();
    }
}
