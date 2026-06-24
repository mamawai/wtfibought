package com.mawai.wiibservice.agent.quant.service;

import com.mawai.wiibcommon.entity.FactorHistory;
import com.mawai.wiibservice.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.series.MarketSeriesStore;
import com.mawai.wiibservice.agent.research.series.SeriesCode;
import com.mawai.wiibservice.mapper.FactorHistoryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResearchFeatureAssemblerTest {

    @Test
    void assemblesPointInTimeFactorsAndQualityFlags() {
        FactorHistoryMapper mapper = mock(FactorHistoryMapper.class);
        ResearchFeatureAssembler assembler = new ResearchFeatureAssembler(mapper);
        LocalDateTime decision = LocalDateTime.of(2026, 1, 10, 12, 0);
        long decisionMs = toMs(decision);

        when(mapper.selectRange(eq("BTCUSDT"), eq(SeriesCode.FUNDING.factorName()), any(), any()))
                .thenReturn(List.of(
                        row(decision.minusHours(8), "0.0001"),
                        row(decision.plusHours(8), "0.0009")));
        when(mapper.selectRange(eq(MarketSeriesStore.GLOBAL), eq(SeriesCode.FEAR_GREED.factorName()), any(), any()))
                .thenReturn(List.of(
                        row(decision.minusDays(1), "45"),
                        row(decision, "60")));
        when(mapper.selectRange(eq("BTCUSDT"), eq(SeriesCode.ETF_FLOW.factorName()), any(), any()))
                .thenReturn(List.of(row(decision.minusDays(3), "123.45")));
        when(mapper.selectRange(eq("BTCUSDT"), eq(SeriesCode.STABLECOIN_DELTA.factorName()), any(), any()))
                .thenReturn(List.of());

        ResearchFeatureAssembler.AssemblyResult result =
                assembler.assemble("BTCUSDT", List.of(bar(decisionMs)));

        ResearchFeatures features = result.features();
        assertThat(features.fundingRate()).isCloseTo(0.0001, within(1e-10));
        assertThat(features.fearGreed()).isEqualTo(60);
        assertThat(features.etfFlow()).isCloseTo(123.45, within(1e-10));
        assertThat(features.stablecoinDelta()).isZero();
        assertThat(result.qualityFlags()).containsExactly("ETF_STALE", "STABLECOIN_MISSING");

        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).selectRange(eq("BTCUSDT"), eq(SeriesCode.FUNDING.factorName()), any(), toCaptor.capture());
        assertThat(toCaptor.getValue()).isEqualTo(decision.plusNanos(1));
    }

    @Test
    void missingFactorsUseNeutralDefaultsAndFlags() {
        FactorHistoryMapper mapper = mock(FactorHistoryMapper.class);
        ResearchFeatureAssembler assembler = new ResearchFeatureAssembler(mapper);

        ResearchFeatureAssembler.AssemblyResult result =
                assembler.assemble("BTCUSDT", List.of(bar(toMs(LocalDateTime.of(2026, 1, 10, 12, 0)))));

        ResearchFeatures features = result.features();
        assertThat(features.fundingRate()).isZero();
        assertThat(features.fearGreed()).isEqualTo(50);
        assertThat(features.etfFlow()).isZero();
        assertThat(features.stablecoinDelta()).isZero();
        assertThat(result.qualityFlags()).containsExactly(
                "FUNDING_MISSING",
                "FNG_MISSING",
                "ETF_MISSING",
                "STABLECOIN_MISSING");
    }

    @Test
    void etfFlowIgnoresCurrentNewYorkDateAtSingaporeMorning() {
        FactorHistoryMapper mapper = mock(FactorHistoryMapper.class);
        ResearchFeatureAssembler assembler = new ResearchFeatureAssembler(mapper);
        LocalDateTime decision = LocalDateTime.of(2026, 6, 10, 0, 5); // SG 08:05, NY 6/9 20:05

        when(mapper.selectRange(eq("BTCUSDT"), eq(SeriesCode.ETF_FLOW.factorName()), any(), any()))
                .thenReturn(List.of(
                        row(LocalDateTime.of(2026, 6, 8, 0, 0), "-91.4"),
                        row(LocalDateTime.of(2026, 6, 9, 0, 0), "0.0")));

        ResearchFeatureAssembler.AssemblyResult result =
                assembler.assemble("BTCUSDT", List.of(bar(toMs(decision))));

        assertThat(result.features().etfFlow()).isCloseTo(-91.4, within(1e-10));

        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).selectRange(eq("BTCUSDT"), eq(SeriesCode.ETF_FLOW.factorName()), any(), toCaptor.capture());
        assertThat(toCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 6, 9, 0, 0));
    }

    @Test
    void emptyBarsReturnNeutralFeaturesWithoutQueryingFactors() {
        FactorHistoryMapper mapper = mock(FactorHistoryMapper.class);
        ResearchFeatureAssembler assembler = new ResearchFeatureAssembler(mapper);

        ResearchFeatureAssembler.AssemblyResult result = assembler.assemble("BTCUSDT", List.of());

        assertThat(result.features().barsUpToNow()).isEmpty();
        assertThat(result.features().fundingRate()).isZero();
        assertThat(result.features().fearGreed()).isEqualTo(50);
        assertThat(result.qualityFlags()).containsExactly("FEATURE_BARS_EMPTY");
        verifyNoInteractions(mapper);
    }

    private static KlineBar bar(long closeTime) {
        long openTime = closeTime - 5 * 60_000L + 1;
        return new KlineBar(openTime, closeTime,
                BigDecimal.valueOf(100), BigDecimal.valueOf(101), BigDecimal.valueOf(99),
                BigDecimal.valueOf(100.5), BigDecimal.TEN);
    }

    private static FactorHistory row(LocalDateTime observedAt, String value) {
        FactorHistory row = new FactorHistory();
        row.setObservedAt(observedAt);
        row.setFactorValue(new BigDecimal(value));
        return row;
    }

    private static long toMs(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
