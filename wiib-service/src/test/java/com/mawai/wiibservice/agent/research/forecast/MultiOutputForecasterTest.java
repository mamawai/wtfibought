package com.mawai.wiibservice.agent.research.forecast;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A1 多输出预测器：asDirectionForecaster 把方向腿适配成现有 Forecaster（方向腿提取 + fit 转发 + 命名）。 */
class MultiOutputForecasterTest {

    private static final ResearchFeatures FEAT = ResearchFeatures.ofBars(List.of());

    @Test
    void directionForecasterReturnsDirectionLeg() {
        MultiOutputForecaster mof = new StubMof(new Forecast(1, 0.7));

        Forecast f = mof.asDirectionForecaster().forecast(FEAT);

        assertThat(f.direction()).isEqualTo(1);
        assertThat(f.confidence()).isEqualTo(0.7);
    }

    @Test
    void directionForecasterDelegatesFit() {
        MultiOutputForecaster mof = new StubMof(Forecast.flat()); // 未训练→方向腿空仓

        Forecaster dir = mof.asDirectionForecaster();
        assertThat(dir.forecast(FEAT).direction()).isZero();             // 训练前

        Forecaster trained = dir.fit(List.of());
        assertThat(trained.forecast(FEAT).direction()).isEqualTo(1);     // 训练后→LONG
        assertThat(trained.forecast(FEAT).confidence()).isEqualTo(0.9);
    }

    @Test
    void directionForecasterNameTagsDirectionLeg() {
        MultiOutputForecaster mof = new StubMof(Forecast.flat());

        assertThat(mof.asDirectionForecaster().name()).contains("stub");
    }

    /** 测试桩：forecast 回放构造时的方向腿；fit 后切到 LONG(0.9)，用于验证 adapter 的 fit 转发。 */
    private record StubMof(Forecast leg) implements MultiOutputForecaster {
        @Override
        public MultiOutputForecaster fit(List<TrainingSample> trainSamples) {
            return new StubMof(new Forecast(1, 0.9));
        }

        @Override
        public MultiOutputForecast forecast(ResearchFeatures features) {
            return new MultiOutputForecast(ForecastHorizon.H6, 0.02, MarketRegime.RANGING, 0.5, leg);
        }

        @Override
        public String name() {
            return "stub";
        }
    }
}
