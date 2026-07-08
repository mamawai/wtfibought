package com.mawai.wiibquant.agent.quant.judge;

import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.MarketRegime;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragileDirection;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityLevel;
import com.mawai.wiibquant.agent.quant.domain.fragility.FragilityScore;
import com.mawai.wiibquant.agent.quant.domain.signal.Signal;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalGroup;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalLean;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FragilityScorerTest {

    private final FragilityScorer scorer = new FragilityScorer();

    @Test
    void calmMarketIsLowFragility() {
        FragilityScore f = scorer.score(
                snapshot(0, 0, 0, 0, 50, 0, 0, 0, null, BigDecimal.valueOf(50000), 0, false, MarketRegime.RANGE),
                SignalPanel.empty());

        assertThat(f.score()).isLessThan(25);
        assertThat(f.level()).isEqualTo(FragilityLevel.LOW);
        assertThat(f.direction()).isEqualTo(FragileDirection.NEUTRAL);
        assertThat(f.headline()).contains("平稳");
    }

    @Test
    void longCrowdingIsDownFragile() {
        FragilityScore f = scorer.score(
                snapshot(0.8, 0.7, 0.6, 0.5, 85, 0, 0, 0, null, BigDecimal.valueOf(50000), 0, false, MarketRegime.RANGE),
                positioning(SignalLean.BEARISH)); // POSITIONING 偏空 = 多头拥挤

        assertThat(f.crowding()).isGreaterThan(0.5);
        assertThat(f.direction()).isEqualTo(FragileDirection.DOWN);
        assertThat(f.headline()).contains("多头拥挤").contains("下行脆弱");
    }

    @Test
    void allLegsMaxedIsExtreme() {
        FragilityScore f = scorer.score(
                snapshot(1, 1, 1, 1, 100, 1, 1e7, 0.1, BigDecimal.valueOf(1000), BigDecimal.valueOf(50000),
                        100, true, MarketRegime.SHOCK),
                positioning(SignalLean.BEARISH));

        assertThat(f.score()).isGreaterThanOrEqualTo(75);
        assertThat(f.level()).isEqualTo(FragilityLevel.EXTREME);
    }

    @Test
    void nullSnapshotReturnsEmpty() {
        FragilityScore f = scorer.score(null, SignalPanel.empty());

        assertThat(f.score()).isZero();
        assertThat(f.level()).isEqualTo(FragilityLevel.LOW);
        assertThat(f.direction()).isEqualTo(FragileDirection.NEUTRAL);
        assertThat(f.headline()).contains("数据不足");
    }

    @Test
    void volStateTakesMaxNotMean() {
        // 仅 regime=SHOCK，其它波动维度为 0：max 语义应得 0.9，而非被稀释
        FragilityScore f = scorer.score(
                snapshot(0, 0, 0, 0, 50, 0, 0, 0, null, BigDecimal.valueOf(50000), 0, false, MarketRegime.SHOCK),
                SignalPanel.empty());

        assertThat(f.volState()).isEqualTo(0.9);
    }

    @Test
    void missingFearGreedDoesNotInflateCrowding() {
        // fearGreed=-1（采集失败哨兵）不能被当成满格拥挤
        FragilityScore f = scorer.score(
                snapshot(0, 0, 0, 0, -1, 0, 0, 0, null, BigDecimal.valueOf(50000), 0, false, MarketRegime.RANGE),
                SignalPanel.empty());

        assertThat(f.crowding()).isZero();
    }

    private static SignalPanel positioning(SignalLean lean) {
        return new SignalPanel(List.of(
                new Signal("HIGH_FUNDING", "x", lean, SignalGroup.POSITIONING, "microstructure")));
    }

    private static FeatureSnapshot snapshot(double funding, double lsr, double topTrader, double taker,
                                            int fearGreed, double liqPressure, double liqVol, double oiChange,
                                            BigDecimal atr, BigDecimal price, double dvol, boolean squeeze,
                                            MarketRegime regime) {
        return new FeatureSnapshot(
                "BTCUSDT", LocalDateTime.of(2026, 1, 1, 0, 0),
                price, null, null, null,
                Map.of(), Map.of(),
                0, null, 0, 0,
                0, 0, 0, 0, oiChange,
                funding, 0, 0, lsr,
                liqPressure, liqVol,
                topTrader, taker, fearGreed, "Neutral",
                null, atr, null, squeeze,
                dvol, 0, 0, 0,
                regime, List.of(), 1.0, "NONE");
    }
}
