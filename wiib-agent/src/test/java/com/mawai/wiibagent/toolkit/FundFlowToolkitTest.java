package com.mawai.wiibagent.toolkit;

import com.mawai.wiibquant.market.service.FundFlowService;
import com.mawai.wiibquant.market.service.FundFlowService.Coin;
import com.mawai.wiibquant.market.service.FundFlowService.EtfDay;
import com.mawai.wiibquant.market.service.FundFlowService.EtfFlows;
import com.mawai.wiibquant.market.service.FundFlowService.Stablecoins;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FundFlowToolkitTest {

    private final FundFlowService service = mock(FundFlowService.class);
    private final FundFlowToolkit toolkit = new FundFlowToolkit(service);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void 两段都有_平铺进两个字段() {
        when(service.etfFlows()).thenReturn(new EtfFlows("2026-10-28", d("104876.7"), d("37.3"),
                List.of(new EtfDay("2026-10-28", d("137.3"))), Map.of("IBIT", d("136.0")), "TFTC"));
        when(service.stablecoins()).thenReturn(new Stablecoins(
                new Coin(d("251741.0"), d("-2913.0"), d("-6400.0")),
                new Coin(d("180240.0"), d("-2210.0"), d("-3353.0")), null));

        JsonNode out = MAPPER.readTree(toolkit.fundFlows());

        assertThat(out.get("available").asBoolean()).isTrue();
        assertThat(out.get("btcSpotEtf").get("daily").get(0).get("netFlowMusd").asDouble()).isEqualTo(137.3);
        assertThat(out.get("btcSpotEtf").get("latestPerEtfMusd").get("IBIT").asDouble()).isEqualTo(136.0);
        assertThat(out.get("stablecoins").get("usdt").get("change7dMusd").asDouble()).isEqualTo(-2210.0);
        // 没找到的币不出现
        assertThat(out.get("stablecoins").has("usdc")).isFalse();
        assertThat(out.has("unavailable")).isFalse();
    }

    @Test
    void 一段没取到列在unavailable_两段都没有回不可用() {
        when(service.stablecoins()).thenReturn(new Stablecoins(
                new Coin(d("1.0"), d("0.0"), d("0.0")), null, null));

        JsonNode out = MAPPER.readTree(toolkit.fundFlows());
        assertThat(out.get("unavailable").get(0).asString()).isEqualTo("btcSpotEtf");
        assertThat(out.has("btcSpotEtf")).isFalse();

        when(service.stablecoins()).thenReturn(null);
        assertThat(toolkit.fundFlows()).contains("\"available\":false");
    }
}
