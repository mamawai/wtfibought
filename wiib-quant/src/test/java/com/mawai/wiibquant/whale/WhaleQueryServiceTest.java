package com.mawai.wiibquant.whale;

import com.mawai.wiibquant.mapper.WhaleSnapshotMapper;
import com.mawai.wiibquant.mapper.WhaleSnapshotMapper.Row;
import com.mawai.wiibquant.whale.WhaleQueryService.CoinView;
import com.mawai.wiibquant.whale.WhaleQueryService.Summary;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 查询层：覆盖率、近价强平带（上方只取空头、下方只取多头、跨价桶两边都算、5% 内无桶回 null）、
 * 一侧没仓位、两侧都没仓位不进 summary、只取最新一槽、开关关掉回空、JSON 键名与平铺。
 */
class WhaleQueryServiceTest {

    /** 价 80100、桶宽 400：80000 那桶跨价；84000 在上方 5% 内（≤84105）、84400 不在；76000 在下方 5% 内（+400>76095）、75600 不在 */
    private static final String LIQ = "{\"width\":400,\"buckets\":[[75600,999000,0],[76000,200000,0],[80000,100000,50000],"
            + "[84000,0,300000],[84400,0,999000]]}";
    private static final String ENTRY = "{\"width\":200,\"buckets\":[[79800,100000,0]]}";

    private final WhaleSnapshotMapper mapper = mock(WhaleSnapshotMapper.class);
    private final WhaleProperties props = new WhaleProperties();

    private WhaleQueryService service() {
        props.setCoins(List.of("BTC", "ETH", "DOGE"));
        return new WhaleQueryService(mapper, props);
    }

    private static Row row(String coin, long observedAt, int longCount, String longNotional, int shortCount, String shortNotional) {
        Row r = new Row();
        r.setObservedAt(observedAt);
        r.setCoin(coin);
        r.setPrice(new BigDecimal("80100"));
        r.setHlOpenInterest(new BigDecimal("1000000000"));
        r.setPoolSize(44);
        r.setLongCount(longCount);
        r.setLongNotional(new BigDecimal(longNotional));
        r.setShortCount(shortCount);
        r.setShortNotional(new BigDecimal(shortNotional));
        if (longCount > 0) {
            r.setLongWavgEntry(new BigDecimal("75620"));
            r.setLongMedianEntry(new BigDecimal("77390"));
            r.setLongTop1Share(new BigDecimal("0.31"));
            r.setLongUpnl(new BigDecimal("1470000"));
        }
        if (shortCount > 0) {
            r.setShortWavgEntry(new BigDecimal("76710"));
            r.setShortMedianEntry(new BigDecimal("78200"));
            r.setShortTop1Share(new BigDecimal("0.25"));
            r.setShortUpnl(new BigDecimal("-3790000"));
        }
        r.setEntryBucketsJson(ENTRY);
        r.setLiqBucketsJson(LIQ);
        return r;
    }

    @Test
    void summary_覆盖率_近价强平带_symbol拼USDT() {
        when(mapper.selectLatest("BTC")).thenReturn(row("BTC", 1_000L, 44, "29000000", 29, "126000000"));
        Summary s = service().summary();

        assertThat(s.observedAt()).isEqualTo(1_000L);
        assertThat(s.poolSize()).isEqualTo(44);
        assertThat(s.coins()).hasSize(1);
        CoinView v = s.coins().getFirst();
        assertThat(v.coin()).isEqualTo("BTC");
        assertThat(v.symbol()).isEqualTo("BTCUSDT");
        assertThat(v.price()).isEqualByComparingTo("80100");
        assertThat(v.longSide().count()).isEqualTo(44);
        assertThat(v.longSide().wavgEntry()).isEqualByComparingTo("75620");
        assertThat(v.shortSide().count()).isEqualTo(29);
        assertThat(v.shortSide().upnl()).isEqualByComparingTo("-3790000");
        assertThat(v.hlOpenInterest()).isEqualByComparingTo("1000000000");
        assertThat(v.coverage().longSide()).isEqualByComparingTo("0.029");
        assertThat(v.coverage().shortSide()).isEqualByComparingTo("0.126");
        // 上方：跨价桶空头 5 万 + 84000 桶 30 万；下方：跨价桶多头 10 万 + 76000 桶 20 万
        assertThat(v.liqAbove().notional()).isEqualByComparingTo("350000");
        assertThat(v.liqAbove().peakPrice()).isEqualByComparingTo("84000");
        assertThat(v.liqBelow().notional()).isEqualByComparingTo("300000");
        assertThat(v.liqBelow().peakPrice()).isEqualByComparingTo("76000");
    }

    @Test
    void 近价强平带_百分之五内没桶回null_只有对侧列也回null() {
        Row r = row("BTC", 1_000L, 1, "200000", 1, "200000");
        // 上方 5% 内只有多头列、下方 5% 内只有空头列：两边都没东西
        r.setLiqBucketsJson("{\"width\":400,\"buckets\":[[76000,0,200000],[84000,200000,0],[90000,0,999000]]}");
        when(mapper.selectLatest("BTC")).thenReturn(r);

        CoinView v = service().summary().coins().getFirst();
        assertThat(v.liqAbove()).isNull();
        assertThat(v.liqBelow()).isNull();
    }

    @Test
    void 一侧没仓位_count0其余null_OI为0覆盖率null() {
        Row r = row("BTC", 1_000L, 3, "500000", 0, "0");
        r.setHlOpenInterest(BigDecimal.ZERO);
        when(mapper.selectLatest("BTC")).thenReturn(r);

        CoinView v = service().summary().coins().getFirst();
        assertThat(v.longSide().count()).isEqualTo(3);
        assertThat(v.shortSide().count()).isZero();
        assertThat(v.shortSide().notional()).isNull();
        assertThat(v.shortSide().wavgEntry()).isNull();
        assertThat(v.coverage().longSide()).isNull();
        assertThat(v.coverage().shortSide()).isNull();
    }

    @Test
    void summary_两侧都没仓位的币不进_只取最新一槽() {
        when(mapper.selectLatest("BTC")).thenReturn(row("BTC", 2_000L, 1, "200000", 0, "0"));
        when(mapper.selectLatest("ETH")).thenReturn(row("ETH", 1_000L, 1, "200000", 1, "200000"));
        when(mapper.selectLatest("DOGE")).thenReturn(row("DOGE", 2_000L, 0, "0", 0, "0"));

        Summary s = service().summary();
        assertThat(s.observedAt()).isEqualTo(2_000L);
        assertThat(s.coins()).extracting(CoinView::coin).containsExactly("BTC");
    }

    @Test
    void summary_没快照_observedAt为null_coins空() {
        Summary s = service().summary();
        assertThat(s.observedAt()).isNull();
        assertThat(s.poolSize()).isNull();
        assertThat(s.coins()).isEmpty();
    }

    @Test
    void 开关关掉_两个都回空_不查表() {
        props.setEnabled(false);
        WhaleQueryService svc = service();
        assertThat(svc.summary().coins()).isEmpty();
        assertThat(svc.coin("BTC")).isNull();
        verifyNoInteractions(mapper);
    }

    @Test
    void coin_不在配置_没快照_没仓位都回null() {
        when(mapper.selectLatest("DOGE")).thenReturn(row("DOGE", 1_000L, 0, "0", 0, "0"));
        WhaleQueryService svc = service();
        assertThat(svc.coin("XAU")).isNull();
        assertThat(svc.coin("ETH")).isNull();
        assertThat(svc.coin("DOGE")).isNull();
    }

    @Test
    void 序列化_long_short键名_detail平铺_分桶原样透传_空侧null() {
        when(mapper.selectLatest("BTC")).thenReturn(row("BTC", 1_000L, 44, "29000000", 0, "0"));
        WhaleQueryService svc = service();
        JsonMapper json = JsonMapper.builder().build();

        String summary = json.writeValueAsString(svc.summary());
        assertThat(summary).contains("\"observedAt\":1000,\"poolSize\":44,\"coins\":[{\"coin\":\"BTC\",\"symbol\":\"BTCUSDT\"")
                .contains("\"long\":{\"count\":44,")
                .contains("\"short\":{\"count\":0,\"notional\":null,")
                .contains("\"liqAbove\":{\"notional\":350000,\"peakPrice\":84000}")
                .contains("\"coverage\":{\"long\":0.0290,\"short\":0.0000}")
                .doesNotContain("longSide");

        String detail = json.writeValueAsString(svc.coin("BTC"));
        assertThat(detail).startsWith("{\"coin\":\"BTC\",\"symbol\":\"BTCUSDT\"")
                .contains("\"observedAt\":1000,\"poolSize\":44")
                .contains("\"entryBuckets\":" + ENTRY)
                .contains("\"liqBuckets\":" + LIQ)
                .doesNotContain("\"view\"");
    }
}
