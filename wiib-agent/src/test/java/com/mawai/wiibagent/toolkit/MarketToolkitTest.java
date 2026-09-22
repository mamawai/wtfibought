package com.mawai.wiibagent.toolkit;
import com.mawai.wiibquant.market.service.MarketAssembly;
import com.mawai.wiibquant.market.service.MarketDataService;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketToolkitTest {

    private final MarketDataService dataService = mock(MarketDataService.class);
    private final MarketToolkit toolkit = new MarketToolkit(dataService);

    /** 共用造件见 TestAssemblies（record 无法可靠 mock，统一真实构造）。 */
    private MarketAssembly assemblyWithSnapshot() {
        return TestAssemblies.available();
    }

    @Test
    void marketSnapshotOutputsKeyFields() {
        when(dataService.assemble("BTCUSDT")).thenReturn(assemblyWithSnapshot());

        String json = toolkit.marketSnapshot("BTCUSDT");

        assertThat(json).contains("65000").contains("fundingDeviation").contains("fearGreed")
                .contains("price_change").contains("RANGE");
    }

    @Test
    void optionIvOutputsSummary() {
        when(dataService.assemble("BTCUSDT")).thenReturn(assemblyWithSnapshot());

        String json = toolkit.optionIv("BTCUSDT");

        assertThat(json).contains("DVOL=52").contains("\"dvolIndex\":52.0");
    }

    /** SOL/XRP 只有期权簿没有 DVOL：不许出 DVOL=0.0 或 dvolIndex:0 让模型当成读数 */
    @Test
    void optionIvWithoutDvolOmitsIt() {
        when(dataService.assemble("SOLUSDT")).thenReturn(TestAssemblies.withIv(0, 78.2));

        String json = toolkit.optionIv("SOLUSDT");

        assertThat(json).contains("ATM_IV=78.2").doesNotContain("DVOL").doesNotContain("dvolIndex");
    }

    @Test
    void unavailableAssemblyDegradesGracefully() {
        when(dataService.assemble("BTCUSDT")).thenReturn(MarketAssembly.unavailable("BTCUSDT", Map.of()));

        assertThat(toolkit.marketSnapshot("BTCUSDT")).contains("\"available\":false");
        assertThat(toolkit.optionIv("BTCUSDT")).contains("\"available\":false");
    }

    /** 取数失败必须给模型一个结构完整的 available:false，而不是半截 JSON 或异常字符串 */
    @Test
    void 资金费取数失败返回不可用JSON() {
        when(dataService.fundingHistory("BTCUSDT")).thenReturn(null);

        String json = toolkit.fundingHistory("BTCUSDT");

        assertThat(json).isEqualTo("{\"available\":false,\"reason\":\"funding data unavailable\"}");
    }

    /** 熔断期两边都只剩过期缓存时工具仍要能正常作答——这是本次改动要救的那条路，也是三个契约字段的锁 */
    @Test
    void 历史与资金费上下文都在时输出完整() {
        when(dataService.fundingHistory("BTCUSDT")).thenReturn("[{\"fundingTime\":1,\"fundingRate\":\"0.0001\"}]");
        when(dataService.premiumIndex("BTCUSDT"))
                .thenReturn("{\"nextFundingTime\":2,\"lastFundingRate\":\"0.0002\",\"markPrice\":\"100\"}");

        String json = toolkit.fundingHistory("BTCUSDT");

        // 三个字段的名字和取值都钉住：数据源刚被换掉，换错了模型是看不出来的
        assertThat(json).contains("\"available\":true")
                .contains("\"nextFundingTime\":2")
                .contains("\"lastFundingRate\":\"0.0002\"")
                .contains("\"markPrice\":\"100\"");
    }

    /** 资金费上下文取不到时也要给结构完整的不可用，而不是让 NPE 冒成一句 Java 异常喂给模型 */
    @Test
    void 资金费上下文取不到时返回不可用JSON() {
        when(dataService.fundingHistory("BTCUSDT")).thenReturn("[]");
        when(dataService.premiumIndex("BTCUSDT")).thenReturn(null);

        String json = toolkit.fundingHistory("BTCUSDT");

        assertThat(json).isEqualTo("{\"available\":false,\"reason\":\"funding data unavailable\"}");
    }

    /** 盘口 WS 和 REST 双双落空时同理：挂限价单的 agent 必须明确知道没数据 */
    @Test
    void 盘口取数失败返回不可用JSON() {
        when(dataService.orderbook("BTCUSDT")).thenReturn(null);

        String json = toolkit.orderbookDepth("BTCUSDT");

        assertThat(json).isEqualTo("{\"available\":false,\"reason\":\"orderbook unavailable\"}");
    }

    /** 数据源是 WS 的 top20，工具描述承诺 top10——不截档就是新造一处"描述与实际不符" */
    @Test
    void 盘口截到前十档() {
        when(dataService.orderbook("BTCUSDT")).thenReturn(depthWithLevels(20));

        JsonNode out = MAPPER.readTree(toolkit.orderbookDepth("BTCUSDT"));

        assertThat(out.get("bids")).hasSize(10);
        assertThat(out.get("asks")).hasSize(10);
        assertThat(out.get("bids").get(0).path(0).asString(null)).isEqualTo("0"); // 仍是最优档打头
    }

    private static String depthWithLevels(int levels) {
        ArrayNode bids = MAPPER.createArrayNode();
        ArrayNode asks = MAPPER.createArrayNode();
        for (int i = 0; i < levels; i++) {
            bids.addArray().add(String.valueOf(i)).add("1");
            asks.addArray().add(String.valueOf(i)).add("1");
        }
        ObjectNode book = MAPPER.createObjectNode();
        book.set("bids", bids);
        book.set("asks", asks);
        return MAPPER.writeValueAsString(book);
    }
}
