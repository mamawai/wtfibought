package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.stats.VolatilityEstimator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A3.2 量化核心：三腿接线（vol=EWMA / regime=分类器 / direction=注入）+ horizon 透传 + fit 转发方向腿。 */
class QuantCoreForecasterTest {

    @Test
    void volLegIsEwmaVolatility() {
        List<KlineBar> bars = uptrend(40);
        QuantCoreForecaster fc = new QuantCoreForecaster(ForecastHorizon.H6, 0.94, new StubDir(Forecast.flat()));

        MultiOutputForecast f = fc.forecast(ResearchFeatures.ofBars(bars));

        assertThat(f.expectedVolatility()).isEqualTo(VolatilityEstimator.ewmaVolatility(bars, 0.94));
    }

    @Test
    void directionLegFromUnderlyingForecaster() {
        QuantCoreForecaster fc = new QuantCoreForecaster(ForecastHorizon.H6, 0.94, new StubDir(new Forecast(1, 0.5)));

        MultiOutputForecast f = fc.forecast(ResearchFeatures.ofBars(uptrend(40)));

        assertThat(f.direction().direction()).isEqualTo(1);
        assertThat(f.direction().confidence()).isEqualTo(0.5);
    }

    @Test
    void horizonPassthrough() {
        QuantCoreForecaster fc = new QuantCoreForecaster(ForecastHorizon.H12, 0.94, new StubDir(Forecast.flat()));

        assertThat(fc.forecast(ResearchFeatures.ofBars(uptrend(40))).horizon()).isEqualTo(ForecastHorizon.H12);
    }

    @Test
    void strongUptrendIsTrendingUpRegime() {
        QuantCoreForecaster fc = new QuantCoreForecaster(ForecastHorizon.H6, 0.94, new StubDir(Forecast.flat()));

        MultiOutputForecast f = fc.forecast(ResearchFeatures.ofBars(uptrend(40)));

        assertThat(f.regime()).isEqualTo(MarketRegime.TRENDING_UP);
    }

    @Test
    void fitDelegatesToDirectionLeg() {
        QuantCoreForecaster fc = new QuantCoreForecaster(ForecastHorizon.H6, 0.94, new FitTrackingDir());

        assertThat(fc.forecast(ResearchFeatures.ofBars(uptrend(40))).direction().direction()).isZero(); // 训练前 flat
        MultiOutputForecaster trained = fc.fit(List.of());
        assertThat(trained.forecast(ResearchFeatures.ofBars(uptrend(40))).direction().direction()).isEqualTo(1); // 训练后 LONG
    }

    static List<KlineBar> uptrend(int n) {
        List<KlineBar> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal c = BigDecimal.valueOf(100.0 + i);
            out.add(new KlineBar(0, 0, c, c, c, c, BigDecimal.ONE));
        }
        return out;
    }

    /** 方向腿桩：恒返回构造时的 Forecast。 */
    private record StubDir(Forecast out) implements Forecaster {
        @Override
        public Forecast forecast(ResearchFeatures features) {
            return out;
        }

        @Override
        public String name() {
            return "stubdir";
        }
    }

    /** 方向腿桩：未训练 flat，fit 后切 LONG(0.9)，验证 QuantCore 把 fit 转发给方向腿。 */
    private record FitTrackingDir() implements Forecaster {
        @Override
        public Forecaster fit(List<TrainingSample> trainSamples) {
            return new StubDir(new Forecast(1, 0.9));
        }

        @Override
        public Forecast forecast(ResearchFeatures features) {
            return Forecast.flat();
        }

        @Override
        public String name() {
            return "fittrack";
        }
    }
}
