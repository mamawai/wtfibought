package com.mawai.wiibservice.agent.trading;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExitPlanRecoveryTest {

    @Test
    void recoversRiskFromLossSideStopWhenDistanceIsReasonable() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 7, 1, 0);
        ExitPlan plan = ExitPlanRecovery.recover(
                position("LONG", "100000", "99000", "BREAKOUT", createdAt),
                market("400"), SymbolProfile.of("BTCUSDT"), LocalDateTime.now());

        assertThat(plan).isNotNull();
        assertThat(plan.path()).isEqualTo(ExitPath.BREAKOUT);
        assertThat(plan.riskPerUnit()).isEqualByComparingTo("1000");
        assertThat(plan.initialSL()).isEqualByComparingTo("99000");
        assertThat(plan.atrAtEntry()).isEqualByComparingTo("400");
        assertThat(plan.createdAt()).isEqualTo(createdAt);
        assertThat(plan.breakevenDone()).isFalse();
        assertThat(plan.recovered()).isTrue();
        assertThat(plan.entryBollPb()).isNull();
        assertThat(plan.entryRsi()).isNull();
        assertThat(plan.entryMa1h()).isNull();
        assertThat(plan.entryMa15m()).isNull();
    }

    @Test
    void estimatesRiskWhenStopIsNearEntry() {
        ExitPlan plan = ExitPlanRecovery.recover(
                position("LONG", "100000", "99990", "BREAKOUT", LocalDateTime.now()),
                market("400"), SymbolProfile.of("BTCUSDT"), LocalDateTime.now());

        assertThat(plan).isNotNull();
        assertThat(plan.breakevenDone()).isTrue();
        assertThat(plan.riskPerUnit()).isEqualByComparingTo("1120.0");
        assertThat(plan.initialSL()).isEqualByComparingTo("98880.0");
    }

    @Test
    void estimatesShortTrendRiskWhenStopAlreadyMovedToBreakeven() {
        ExitPlan plan = ExitPlanRecovery.recover(
                position("SHORT", "100000", "100010", "LEGACY_TREND", LocalDateTime.now()),
                market("400"), SymbolProfile.of("BTCUSDT"), LocalDateTime.now());

        assertThat(plan).isNotNull();
        assertThat(plan.path()).isEqualTo(ExitPath.TREND);
        assertThat(plan.breakevenDone()).isTrue();
        assertThat(plan.riskPerUnit()).isEqualByComparingTo("1040.0");
        assertThat(plan.initialSL()).isEqualByComparingTo("101040.0");
    }

    private static FuturesPositionDTO position(String side, String entry, String sl, String memo,
                                               LocalDateTime createdAt) {
        FuturesPositionDTO pos = new FuturesPositionDTO();
        pos.setId(1L);
        pos.setSide(side);
        pos.setEntryPrice(bd(entry));
        pos.setQuantity(BigDecimal.ONE);
        pos.setStatus("OPEN");
        pos.setMemo(memo);
        pos.setCreatedAt(createdAt);
        pos.setStopLosses(List.of(new FuturesStopLoss("sl-1", bd(sl), BigDecimal.ONE)));
        return pos;
    }

    private static MarketContext market(String atr) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "regime": "TREND",
                  "atr5m": %s,
                  "indicatorsByTimeframe": {"5m": {"volume_ratio": 1.0}}
                }
                """.formatted(atr));
        return MarketContext.parse(forecast, bd("100000"));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
