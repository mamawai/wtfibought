package com.mawai.wiibquant.agent.toolkit;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.quant.service.ResearchFeatureAssembler;
import com.mawai.wiibquant.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibquant.mapper.QuantSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuantSnapshotServiceTest {

    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final KlineHistoryStore historyStore = mock(KlineHistoryStore.class);
    private final ResearchFeatureAssembler featureAssembler = mock(ResearchFeatureAssembler.class);
    private final QuantSnapshotMapper mapper = mock(QuantSnapshotMapper.class);

    private QuantSnapshotService service() {
        return new QuantSnapshotService(marketDataService, historyStore, featureAssembler, mapper);
    }

    /** 90 天 5m 合成 bar：正弦噪声价格，保证 vol/regime 腿有非退化输出。 */
    private List<KlineBar> syntheticBars(long endTime) {
        List<KlineBar> bars = new ArrayList<>();
        long step = Duration.ofMinutes(5).toMillis();
        long start = endTime - Duration.ofDays(90).toMillis();
        double price = 60000;
        int i = 0;
        for (long t = start; t < endTime; t += step, i++) {
            price = price * (1 + 0.001 * Math.sin(i * 0.7));
            BigDecimal p = BigDecimal.valueOf(price);
            bars.add(new KlineBar(t, t + step - 1, p, p, p, p, BigDecimal.ONE));
        }
        return bars;
    }

    @Test
    void buildSnapshotProducesThreeLegsWithPitCuts() {
        long closeTime = 1_800_000_000_000L;
        List<KlineBar> bars = syntheticBars(closeTime + 1);
        when(marketDataService.assemble("BTCUSDT")).thenReturn(TestAssemblies.available());
        when(historyStore.load(eq("BTCUSDT"), anyString(), anyLong(), anyLong())).thenReturn(bars);
        when(featureAssembler.assemble(eq("BTCUSDT"), any()))
                .thenReturn(new ResearchFeatureAssembler.AssemblyResult(ResearchFeatures.ofBars(bars), List.of()));

        QuantSnapshot snap = service().buildSnapshot("BTCUSDT", closeTime);

        assertThat(snap).isNotNull();
        assertThat(snap.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(snap.getCloseTime()).isEqualTo(closeTime);
        assertThat(snap.getFragilityScore()).isEqualTo(61);
        JSONObject legs = JSONObject.parseObject(snap.getVolLegsJson());
        assertThat(legs.keySet()).containsExactlyInAnyOrder("H6", "H12", "H24");
        JSONObject h6 = legs.getJSONObject("H6");
        assertThat(h6.containsKey("sigmaBps")).isTrue();
        assertThat(h6.containsKey("volState")).isTrue();
        assertThat(h6.containsKey("lowCut")).isTrue();   // PIT 档界必须入库
        assertThat(h6.containsKey("highCut")).isTrue();
        // 铁律：direction 腿在快照层丢弃
        assertThat(snap.getVolLegsJson()).doesNotContainIgnoringCase("direction");
    }

    @Test
    void buildSnapshotReturnsNullWhenMarketUnavailable() {
        when(marketDataService.assemble("BTCUSDT")).thenReturn(MarketAssembly.unavailable("BTCUSDT", Map.of()));

        assertThat(service().buildSnapshot("BTCUSDT", 1L)).isNull();
    }

    @Test
    void buildSnapshotReturnsNullWhenHistoryTooShort() {
        when(marketDataService.assemble("BTCUSDT")).thenReturn(TestAssemblies.available());
        when(historyStore.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of());

        assertThat(service().buildSnapshot("BTCUSDT", 1L)).isNull();
    }

    @Test
    void persistIsIdempotentOnDuplicate() {
        QuantSnapshot snap = new QuantSnapshot();
        snap.setSymbol("BTCUSDT");
        snap.setCloseTime(1L);
        QuantSnapshot existing = new QuantSnapshot();
        existing.setId(9L);
        when(mapper.insert(any(QuantSnapshot.class))).thenThrow(new DuplicateKeyException("uk"));
        when(mapper.selectOne(any())).thenReturn(existing);

        Long id = service().persist(snap);

        assertThat(id).isEqualTo(9L);
    }
}
