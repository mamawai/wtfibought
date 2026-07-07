package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import com.mawai.wiibquant.agent.quant.service.MacroContextService;
import com.mawai.wiibquant.config.DeribitClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MarketDataServiceTest {

    private final BinanceRestClient binanceRestClient = mock(BinanceRestClient.class);
    private final ForceOrderService forceOrderService = mock(ForceOrderService.class);
    private final DepthStreamCache depthStreamCache = mock(DepthStreamCache.class);
    private final DeribitClient deribitClient = mock(DeribitClient.class);
    private final OrderFlowAggregator orderFlowAggregator = mock(OrderFlowAggregator.class);
    private final MacroContextService macroContextService = mock(MacroContextService.class);

    private MarketDataService service(long ttlMillis) {
        return new MarketDataService(binanceRestClient, forceOrderService, depthStreamCache,
                deribitClient, orderFlowAggregator, macroContextService, KlineInterval.M5, ttlMillis);
    }

    @Test
    void unavailableWhenCollectReturnsNothing() {
        // 全部 mock 默认返回 null → collect 判 data_available=false → 组装降级为 unavailable
        MarketAssembly assembly = service(60_000).assemble("BTCUSDT");

        assertThat(assembly.available()).isFalse();
        assertThat(assembly.snapshot()).isNull();
        assertThat(assembly.fragility().score()).isZero();
    }

    @Test
    void cachesAssemblyWithinTtl() {
        MarketDataService service = service(60_000);

        MarketAssembly first = service.assemble("BTCUSDT");
        MarketAssembly second = service.assemble("BTCUSDT");

        // TTL 内同一实例直接复用，不重复采集
        assertThat(second).isSameAs(first);
    }

    @Test
    void refreshesWhenTtlExpired() {
        MarketDataService service = service(0); // ttl=0 → 每次都过期

        MarketAssembly first = service.assemble("BTCUSDT");
        MarketAssembly second = service.assemble("BTCUSDT");

        assertThat(second).isNotSameAs(first);
    }

    @Test
    void normalizesSymbol() {
        MarketDataService service = service(60_000);

        MarketAssembly a = service.assemble(" btcusdt ");
        MarketAssembly b = service.assemble("BTCUSDT");

        assertThat(a.symbol()).isEqualTo("BTCUSDT");
        assertThat(b).isSameAs(a); // 归一化后命中同一缓存
    }
}
