package com.mawai.wiibquant.market.service;

import com.mawai.wiibquant.external.defillama.DefiLlamaClient;
import com.mawai.wiibquant.external.tftc.TftcClient;
import com.mawai.wiibquant.market.service.FundFlowService.EtfFlows;
import com.mawai.wiibquant.market.service.FundFlowService.Stablecoins;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundFlowServiceTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final String TFTC = """
            {"attribution":"The Commoner Bitcoin ETF Tracker by TFTC, tftc.io/bitcoin-etf-flows","updatedThrough":"2026-10-28",
             "days":[{"date":"2026-10-27","netFlowUsd":-100000000,"totalNetAssetsUsd":104000000000,"perEtfUsd":{"IBIT":-100000000,"FBTC":0}},
                     {"date":"2026-10-28","netFlowUsd":137330298.95,"totalNetAssetsUsd":104876683004.6,"perEtfUsd":{"BTC":0,"IBIT":136009040,"BITB":1321258.95}},
                     {"date":"2026-10-29","netFlowUsd":null,"totalNetAssetsUsd":null,"perEtfUsd":{"IBIT":null}}]}""";
    private static final String LLAMA = """
            {"peggedAssets":[
              {"symbol":"USDT","gecko_id":"tether","pegType":"peggedUSD","circulating":{"peggedUSD":180240000000},"circulatingPrevWeek":{"peggedUSD":182450000000},"circulatingPrevMonth":{"peggedUSD":183593000000}},
              {"symbol":"USDC","gecko_id":"usd-coin","pegType":"peggedUSD","circulating":{"peggedUSD":71500000000},"circulatingPrevWeek":{"peggedUSD":72204000000},"circulatingPrevMonth":{"peggedUSD":74533000000}},
              {"symbol":"EURC","gecko_id":"euro-coin","pegType":"peggedEUR","circulating":{"peggedEUR":300000000}},
              {"symbol":"NEW","gecko_id":"new","pegType":"peggedUSD","circulating":{"peggedUSD":1000000}}]}""";

    private final TftcClient tftc = mock(TftcClient.class);
    private final DefiLlamaClient llama = mock(DefiLlamaClient.class);
    private final FundFlowService service = new FundFlowService(tftc, llama);

    @Test
    void ETF_没披露的那天不算_逐日_累计_总净资产_最近一天各基金去掉为0的() {
        when(tftc.etfFlows()).thenReturn(TFTC);

        EtfFlows etf = service.etfFlows();

        assertThat(etf.updatedThrough()).isEqualTo("2026-10-28");
        assertThat(etf.daily()).hasSize(2);
        assertThat(etf.daily().getLast().date()).isEqualTo("2026-10-28");
        assertThat(etf.daily().getLast().netFlowMusd()).isEqualByComparingTo("137.3");
        assertThat(etf.cumulativeNetFlowMusd()).isEqualByComparingTo("37.3");
        assertThat(etf.totalNetAssetsMusd()).isEqualByComparingTo("104876.7");
        assertThat(etf.latestPerEtfMusd()).containsOnlyKeys("IBIT", "BITB");
        assertThat(etf.latestPerEtfMusd().get("BITB")).isEqualByComparingTo("1.3");
        assertThat(etf.attribution()).contains("TFTC");
    }

    @Test
    void 稳定币_只算美元挂钩的_合计与USDT_USDC各自的7天30天增减() {
        when(llama.stablecoins()).thenReturn(LLAMA);

        Stablecoins s = service.stablecoins();

        assertThat(s.total().mcapMusd()).isEqualByComparingTo("251741.0");
        assertThat(s.total().change7dMusd()).isEqualByComparingTo("-2913.0");
        assertThat(s.usdt().change7dMusd()).isEqualByComparingTo("-2210.0");
        assertThat(s.usdc().change30dMusd()).isEqualByComparingTo("-3033.0");
    }

    @Test
    void 一小时内复用_过期取不到兜一天内的旧值_再久就null() {
        when(tftc.etfFlows()).thenReturn(TFTC);
        service.nowMs = () -> T0;
        EtfFlows first = service.etfFlows();
        service.nowMs = () -> T0 + 30 * 60_000L;

        assertThat(service.etfFlows()).isSameAs(first);
        verify(tftc, times(1)).etfFlows();

        when(tftc.etfFlows()).thenReturn(null);
        service.nowMs = () -> T0 + 2 * 3_600_000L;
        assertThat(service.etfFlows()).isSameAs(first);
        service.nowMs = () -> T0 + 86_400_000L;
        assertThat(service.etfFlows()).isNull();
    }
}
