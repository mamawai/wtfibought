package com.mawai.wiibagent.toolkit;

import com.mawai.wiibquant.whale.WhaleAggregator.Side;
import com.mawai.wiibquant.whale.WhaleQueryService;
import com.mawai.wiibquant.whale.WhaleQueryService.CoinDetail;
import com.mawai.wiibquant.whale.WhaleQueryService.CoinView;
import com.mawai.wiibquant.whale.WhaleQueryService.Coverage;
import com.mawai.wiibquant.whale.WhaleQueryService.LiqBand;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WhaleToolkitTest {

    /** 2025-09-04 15:33:20 UTC = 北京 23:33:20 */
    private static final long OBSERVED = 1_757_000_000_000L;

    private final WhaleQueryService whales = mock(WhaleQueryService.class);
    private final WhaleToolkit toolkit = new WhaleToolkit(whales);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static CoinDetail btc() {
        Side longSide = new Side(44, d("41900000"), d("75620"), d("77390"), d("0.31"), d("1470000"));
        Side shortSide = new Side(29, d("182900000"), d("76710"), d("78200"), d("0.25"), d("-3790000"));
        CoinView view = new CoinView("BTC", "BTCUSDT", d("78291.5"), longSide, shortSide,
                new LiqBand(d("95000000"), d("80200")), null, d("1450000000"), new Coverage(d("0.029"), d("0.126")));
        // 开仓桶：多头有仓的 4 桶，空头有仓的 6 桶（81000 那桶最小，取前 5 时该落下）
        String entry = """
                {"width":195.7,"buckets":[[71000,0,40000000],[75600,20000000,0],[76000,5000000,3000000],
                 [77000,8000000,0],[78900,1000000,30000000],[80000,0,25000000],[80200,0,20000000],[81000,0,1000000]]}""";
        String liq = """
                {"width":391.5,"buckets":[[76100,12000000,0],[80200,0,95000000]]}""";
        return new CoinDetail(view, OBSERVED, 812, entry, liq);
    }

    @Test
    void 币对映射成币码_汇总平铺_分桶每边取名义最大的5桶() {
        when(whales.coin("BTC")).thenReturn(btc());

        JsonNode out = MAPPER.readTree(toolkit.whalePositions("BTCUSDT"));

        assertThat(out.get("available").asBoolean()).isTrue();
        assertThat(out.get("observedAt").asString()).isEqualTo("2025-09-04 23:33");
        assertThat(out.get("poolSize").asInt()).isEqualTo(812);
        assertThat(out.get("long").get("count").asInt()).isEqualTo(44);
        assertThat(out.get("short").get("wavgEntry").asInt()).isEqualTo(76710);
        assertThat(out.get("liqAbove").get("peakPrice").asInt()).isEqualTo(80200);
        // 5% 内没有多头强平：字段不出现
        assertThat(out.has("liqBelow")).isFalse();
        assertThat(out.get("coverage").get("short").asDouble()).isEqualTo(0.126);

        JsonNode entryLong = out.get("entryClusters").get("long");
        assertThat(entryLong).hasSize(4);
        assertThat(entryLong.get(0).get("price").asInt()).isEqualTo(75600);
        JsonNode entryShort = out.get("entryClusters").get("short");
        assertThat(entryShort).hasSize(5);
        assertThat(entryShort.get(0).get("price").asInt()).isEqualTo(71000);
        assertThat(entryShort.toString()).doesNotContain("81000");
        assertThat(out.get("liqClusters").get("bucketWidth").asDouble()).isEqualTo(391.5);
        assertThat(out.get("liqClusters").get("short").get(0).get("notional").asLong()).isEqualTo(95_000_000L);
    }

    @Test
    void 没快照的币回不可用() {
        when(whales.coin("PEPE")).thenReturn(null);

        assertThat(toolkit.whalePositions("PEPEUSDT")).contains("\"available\":false").contains("PEPE");
    }
}
