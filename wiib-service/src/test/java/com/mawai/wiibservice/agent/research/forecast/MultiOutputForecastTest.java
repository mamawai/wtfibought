package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A1 多输出预测契约：中性工厂 / 构造校验 / 可交易判定 / 派生定仓提示。 */
class MultiOutputForecastTest {

    @Test
    void neutralIsRangingFlatZeroVol() {
        MultiOutputForecast f = MultiOutputForecast.neutral(ForecastHorizon.H6);

        assertThat(f.horizon()).isEqualTo(ForecastHorizon.H6);
        assertThat(f.expectedVolatility()).isZero();
        assertThat(f.volatilityContext().riskTier()).isEqualTo(VolatilityRiskTier.UNKNOWN);
        assertThat(f.volatilityContext().riskFlags()).contains("VOL_HISTORY_SHORT");
        assertThat(f.regime()).isEqualTo(MarketRegime.RANGING);
        assertThat(f.regimeConfidence()).isZero();
        assertThat(f.direction().direction()).isZero();
        assertThat(f.direction().confidence()).isZero();
    }

    @Test
    void rejectsNegativeVolatility() {
        assertThatThrownBy(() -> new MultiOutputForecast(
                ForecastHorizon.H6, -0.01, MarketRegime.RANGING, 0.5, Forecast.flat()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteVolatility() {
        assertThatThrownBy(() -> new MultiOutputForecast(
                ForecastHorizon.H6, Double.NaN, MarketRegime.RANGING, 0.5, Forecast.flat()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRegimeConfidenceOutOfRange() {
        assertThatThrownBy(() -> new MultiOutputForecast(
                ForecastHorizon.H6, 0.01, MarketRegime.RANGING, 1.5, Forecast.flat()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MultiOutputForecast(
                ForecastHorizon.H6, 0.01, MarketRegime.RANGING, -0.1, Forecast.flat()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullComponents() {
        assertThatThrownBy(() -> new MultiOutputForecast(
                null, 0.01, MarketRegime.RANGING, 0.5, Forecast.flat()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MultiOutputForecast(
                ForecastHorizon.H6, 0.01, null, 0.5, Forecast.flat()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MultiOutputForecast(
                ForecastHorizon.H6, 0.01, MarketRegime.RANGING, 0.5, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMismatchedVolatilityContext() {
        VolatilityRiskContext context = VolatilityRiskContext.from(ForecastHorizon.H12, 0.01, java.util.List.of());

        assertThatThrownBy(() -> new MultiOutputForecast(
                ForecastHorizon.H6, 0.01, context, MarketRegime.RANGING, 0.5, Forecast.flat()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tradeableWhenDirectionNonZero() {
        MultiOutputForecast longF = new MultiOutputForecast(
                ForecastHorizon.H6, 0.02, MarketRegime.TRENDING_UP, 0.8, new Forecast(1, 0.7));
        MultiOutputForecast flatF = new MultiOutputForecast(
                ForecastHorizon.H6, 0.02, MarketRegime.RANGING, 0.8, Forecast.flat());

        assertThat(longF.isTradeable()).isTrue();
        assertThat(flatF.isTradeable()).isFalse();
    }

    @Test
    void positionScaleIsConfidenceWhenTradeableElseZero() {
        MultiOutputForecast longF = new MultiOutputForecast(
                ForecastHorizon.H6, 0.02, MarketRegime.TRENDING_UP, 0.8, new Forecast(1, 0.7));
        MultiOutputForecast flatF = new MultiOutputForecast(
                ForecastHorizon.H6, 0.02, MarketRegime.RANGING, 0.8, Forecast.flat());

        assertThat(longF.suggestedPositionScale()).isEqualTo(0.7);
        assertThat(flatF.suggestedPositionScale()).isZero();
    }
}
