package com.mawai.wiibquant.agent.research.metrics;

import com.mawai.wiibquant.agent.research.factor.ContinuousFactorVector;
import com.mawai.wiibquant.agent.research.forecast.MarketRegime;
import com.mawai.wiibquant.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibquant.agent.research.forecast.TrainingSample;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RegimeFeatureDiagnosticsTest {

    @Test
    void ranksSeparatedFeatureByEtaSquared() {
        RegimeFeatureDiagnostics.Report report = RegimeFeatureDiagnostics.evaluate(List.of(
                sample(MarketRegime.RANGING, -0.10),
                sample(MarketRegime.RANGING, 0.00),
                sample(MarketRegime.RANGING, 0.10),
                sample(MarketRegime.TRENDING_UP, 2.90),
                sample(MarketRegime.TRENDING_UP, 3.00),
                sample(MarketRegime.TRENDING_UP, 3.10)
        ));

        assertThat(report.n()).isEqualTo(6);
        assertThat(report.classCounts()).containsEntry(MarketRegime.RANGING, 3)
                .containsEntry(MarketRegime.TRENDING_UP, 3)
                .containsEntry(MarketRegime.TRENDING_DOWN, 0)
                .containsEntry(MarketRegime.SHOCK, 0);

        RegimeFeatureDiagnostics.FeatureReport top = report.topFeatures(1).get(0);
        assertThat(top.name()).isEqualTo("risk_adjusted_momentum");
        assertThat(top.etaSquared()).isGreaterThan(0.99);
        assertThat(top.byRegime().get(MarketRegime.RANGING).p50()).isCloseTo(0.0, within(1e-12));
        assertThat(top.byRegime().get(MarketRegime.TRENDING_UP).p50()).isCloseTo(3.0, within(1e-12));
    }

    @Test
    void skipsSamplesWithoutRealizedRegime() {
        RegimeFeatureDiagnostics.Report report = RegimeFeatureDiagnostics.evaluate(List.of(
                sample(MarketRegime.RANGING, 0.0),
                new TrainingSample(features(99.0), BigDecimal.ZERO)
        ));

        assertThat(report.n()).isEqualTo(1);
        assertThat(report.classCounts().get(MarketRegime.RANGING)).isEqualTo(1);
    }

    private static TrainingSample sample(MarketRegime regime, double riskAdjustedMomentum) {
        return new TrainingSample(features(riskAdjustedMomentum), BigDecimal.ZERO, 0.0, regime);
    }

    private static ResearchFeatures features(double riskAdjustedMomentum) {
        ContinuousFactorVector vector = new ContinuousFactorVector(
                riskAdjustedMomentum, 0.0, 0.0, 0.0, 0.0, 0.0);
        return new ResearchFeatures(List.of(), 0.0, 50, 0.0, 0.0, vector);
    }
}
