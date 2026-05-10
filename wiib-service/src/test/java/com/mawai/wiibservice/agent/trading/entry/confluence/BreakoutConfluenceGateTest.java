package com.mawai.wiibservice.agent.trading.entry.confluence;

import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibservice.agent.trading.runtime.MarketContext;
import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;
import com.mawai.wiibservice.agent.trading.entry.model.EntryStrategyContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BreakoutConfluenceGateTest {

    private final BreakoutConfluenceGate gate = new BreakoutConfluenceGate();

    @Test
    void earlyBreakoutPassedByCoarseFilterCanPassFineGate() {
        ConfluenceGateResult result = gate.evaluate(context(
                "89", "1.20", true, "rising", "golden", "2", "1", "99900", 1, "0.00"));

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(5);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.required()).isEqualTo(3);
        assertThat(result.hitSummary()).doesNotContain("强突破位置", "量能放大");
    }

    @Test
    void weakExtraEvidenceFailsFineGateEvenAfterCoarseShapeExists() {
        ConfluenceGateResult result = gate.evaluate(context(
                "89", "1.20", false, "flat", "death", "1", "2", "101000", -1, "0.00"));

        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isLessThan(3);
    }

    @Test
    void strongConfirmedBreakoutStillPassesFineGate() {
        ConfluenceGateResult result = gate.evaluate(context(
                "98", "1.60", false, "rising_3", "golden", "2", "1", "99900", 1, "0.20"));

        assertThat(result.passed()).isTrue();
        assertThat(result.hitSummary()).contains("突破动能同向", "压缩/强释放环境", "EMA20同侧");
    }

    private static EntryStrategyContext context(String bollPb,
                                                String volumeRatio,
                                                boolean squeeze,
                                                String closeTrend,
                                                String macdCross,
                                                String macdDif,
                                                String macdDea,
                                                String ema20,
                                                int ma15m,
                                                String micro) {
        QuantForecastCycle forecast = new QuantForecastCycle();
        forecast.setSnapshotJson("""
                {
                  "atr5m": 400,
                  "bollSqueeze": %s,
                  "bidAskImbalance": %s,
                  "takerBuySellPressure": 0,
                  "indicatorsByTimeframe": {
                    "5m": {
                      "boll_pb": %s,
                      "volume_ratio": %s,
                      "close_trend": "%s",
                      "macd_cross": "%s",
                      "macd_dif": %s,
                      "macd_dea": %s,
                      "ema20": %s
                    },
                    "15m": {"ma_alignment": %d}
                  }
                }
                """.formatted(squeeze, micro, bollPb, volumeRatio, closeTrend,
                macdCross, macdDif, macdDea, ema20, ma15m));
        MarketContext market = MarketContext.parse(forecast, new BigDecimal("100000"));
        return new EntryStrategyContext(market, SymbolProfile.of("BTCUSDT"), "LONG", true, 0.65);
    }
}
