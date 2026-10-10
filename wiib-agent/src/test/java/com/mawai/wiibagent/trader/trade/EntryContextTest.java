package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.mapper.EconCalendarMapper;
import com.mawai.wiibquant.market.domain.FeatureSnapshot;
import com.mawai.wiibquant.market.domain.MarketRegime;
import com.mawai.wiibquant.market.service.KlineFetcher;
import com.mawai.wiibquant.market.service.MarketAssembly;
import com.mawai.wiibquant.market.service.MarketDataService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 开仓上下文快照：四路外部数据各自取不到只缺那个字段，基础字段永远在，不抛异常。
 */
class EntryContextTest {

    private static final long NOW = 1791542760000L;

    private final MarketDataService dataService = mock(MarketDataService.class);
    private final KlineFetcher klineFetcher = mock(KlineFetcher.class);
    private final EconCalendarMapper calendarMapper = mock(EconCalendarMapper.class);
    private final BinanceRestClient binance = mock(BinanceRestClient.class);
    private final EntryContext ctx = new EntryContext(dataService, klineFetcher, calendarMapper, binance);

    @Test
    void capturesEveryDimensionWhenDataIsThere() {
        FeatureSnapshot snapshot = mock(FeatureSnapshot.class);
        when(snapshot.regime()).thenReturn(MarketRegime.TREND_UP);
        when(dataService.assemble("ETHUSDT")).thenReturn(
                new MarketAssembly("ETHUSDT", true, Map.of(), Map.of(), snapshot, Instant.now()));
        when(klineFetcher.fetch(eq("ETHUSDT"), eq("1h"), anyInt())).thenReturn(bars(40));
        EconCalendarMapper.Row far = new EconCalendarMapper.Row();
        far.setEventTime(NOW + 5 * 3_600_000L);
        far.setTitle("FOMC");
        EconCalendarMapper.Row near = new EconCalendarMapper.Row();
        near.setEventTime(NOW + 30 * 60_000L);
        near.setTitle("CPI");
        when(calendarMapper.selectWindow(anyLong(), anyLong())).thenReturn(List.of(far, near));
        when(binance.getPremiumIndex("ETHUSDT")).thenReturn("{\"lastFundingRate\":\"0.00010000\"}");

        JsonNode json = MAPPER.readTree(ctx.capture("ETHUSDT", "LIMIT",
                new BigDecimal("2504"), new BigDecimal("2530"), 20, NOW));

        assertThat(json.path("orderType").asString()).isEqualTo("LIMIT");
        assertThat(json.path("leverage").asInt()).isEqualTo(20);
        assertThat(json.path("hourBj").asInt())
                .isEqualTo(Instant.ofEpochMilli(NOW).atZone(WakeWindow.ZONE).getHour());
        assertThat(json.path("stopPct").asDecimal()).isEqualByComparingTo("1.038");
        assertThat(json.path("regime").asString()).isEqualTo("TREND_UP");
        assertThat(json.path("atr1h").asDecimal()).isPositive();
        assertThat(json.path("stopAtr").asDecimal()).isPositive();
        assertThat(json.path("eventMin").asLong()).isEqualTo(30);
        assertThat(json.path("eventTitle").asString()).isEqualTo("CPI");
        assertThat(json.path("funding").asString()).isEqualTo("0.00010000");
    }

    @Test
    void externalFailuresOnlyDropTheirOwnFields() {
        when(dataService.assemble(anyString())).thenThrow(new IllegalStateException("down"));
        when(klineFetcher.fetch(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(calendarMapper.selectWindow(anyLong(), anyLong())).thenThrow(new IllegalStateException("db"));
        when(binance.getPremiumIndex(anyString())).thenThrow(new IllegalStateException("451"));

        JsonNode json = MAPPER.readTree(ctx.capture("BTCUSDT", "MARKET",
                new BigDecimal("82106.7"), new BigDecimal("83693"), 80, NOW));

        assertThat(json.path("orderType").asString()).isEqualTo("MARKET");
        assertThat(json.path("leverage").asInt()).isEqualTo(80);
        assertThat(json.path("stopPct").asDecimal()).isEqualByComparingTo("1.932");
        assertThat(json.has("regime")).isFalse();
        assertThat(json.has("atr1h")).isFalse();
        assertThat(json.has("stopAtr")).isFalse();
        assertThat(json.has("eventMin")).isFalse();
        assertThat(json.has("funding")).isFalse();
    }

    /** n 根 1h 合成 K 线，价位在 2500 附近小幅波动，ATR 算得出且为正 */
    private static List<KlineBar> bars(int n) {
        List<KlineBar> out = new ArrayList<>();
        long t = NOW - n * 3_600_000L;
        for (int i = 0; i < n; i++, t += 3_600_000L) {
            BigDecimal base = new BigDecimal(2500 + (i % 5) * 3);
            out.add(new KlineBar(t, t + 3_600_000L - 1, base, base.add(BigDecimal.TEN),
                    base.subtract(BigDecimal.TEN), base.add(BigDecimal.ONE), BigDecimal.valueOf(100)));
        }
        return out;
    }
}
