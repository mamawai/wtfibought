package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.MarketRegime;
import com.mawai.wiibservice.agent.research.forecast.QuantCoreForecaster;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A3.3 多输出评估编排：三输出收集一致 + 方向尺子复用 + regime/vol 接线 sanity（合成数据，离线）。 */
class MultiOutputEvalServiceTest {

    @Test
    void producesAllThreeOutputsWithConsistentTestPoints() {
        EvalParams params = new EvalParams(1.5, 0.94, 2, 0, 2, 100, 0.95, 42L);

        MultiOutputReport r = MultiOutputEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(8 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                QuantCoreForecaster.defaults(ForecastHorizon.H6), params);

        assertThat(r.symbol()).isEqualTo("BTCUSDT");
        assertThat(r.forecasterName()).contains("quant_core");
        assertThat(r.horizonHours()).isEqualTo(6);
        assertThat(r.testPoints()).isGreaterThan(0);
        assertThat(r.volScore().n()).isEqualTo(r.testPoints());
        assertThat(r.volBaselines()).containsKeys("randomWalk", "prevEwma", "rolling3d", "constant");
        assertThat(r.volBaselines().values()).allSatisfy(b -> assertThat(b.n()).isEqualTo(r.testPoints()));
        assertThat(r.bestVolBaselineName()).isNotBlank();
        assertThat(r.volQlikeVsBestBaseline().n()).isEqualTo(r.testPoints());
        assertThat(r.volQlikeVsBestBaseline().pValue()).isBetween(0.0, 1.0);
        assertThat(r.volCalibration().n()).isEqualTo(r.testPoints());
        assertThat(r.volCalibration().buckets()).isNotEmpty();
        assertThat(r.volRiskSummary().n()).isEqualTo(r.testPoints());
        assertThat(r.volRiskSummary().llmContext()).contains("vol_context_summary");
        assertThat(r.regimeScore().n()).isEqualTo(r.testPoints());
        assertThat(r.directionMetrics().periods()).isEqualTo(r.testPoints());
        assertThat(r.directionPathSummary().tradedPoints()).isBetween(0, r.testPoints());
    }

    @Test
    void directionLegPopulatesBuyHoldAndNaivePercentile() {
        EvalParams params = new EvalParams(1.5, 0.94, 2, 0, 2, 100, 0.95, 42L);

        MultiOutputReport r = MultiOutputEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(8 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                QuantCoreForecaster.defaults(ForecastHorizon.H6), params);

        assertThat(r.buyAndHoldReturn().doubleValue()).isGreaterThan(0); // 上行趋势
        assertThat(r.directionNaivePercentile()).isBetween(0.0, 1.0);
    }

    @Test
    void strongUptrendRegimeAccuracyIsHigh() {
        EvalParams params = new EvalParams(1.5, 0.94, 5, 0, 30, 100, 0.95, 42L);

        MultiOutputReport r = MultiOutputEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(40 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                QuantCoreForecaster.defaults(ForecastHorizon.H6), params);

        // 持续上涨：regime 预测(ADX 高→UP) 与事后标签(平滑路径 directionality≫q→UP) 大量吻合
        assertThat(r.regimeScore().accuracy()).isGreaterThan(0.5);
    }

    @Test
    void volScoresComputedForModelAndNaive() {
        EvalParams params = new EvalParams(1.5, 0.94, 5, 0, 30, 100, 0.95, 42L);

        MultiOutputReport r = MultiOutputEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(40 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                QuantCoreForecaster.defaults(ForecastHorizon.H6), params);

        assertThat(r.volScore().n()).isGreaterThan(0);
        assertThat(r.volScore().qlike()).isGreaterThanOrEqualTo(0.0);
        assertThat(r.volScore().mse()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void reportCarriesRegimeDistributionsSummingToTestPoints() {
        EvalParams params = new EvalParams(1.5, 0.94, 5, 0, 30, 100, 0.95, 42L);

        MultiOutputReport r = MultiOutputEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(40 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                QuantCoreForecaster.defaults(ForecastHorizon.H6), params);

        // 分布是诊断 regime 塌缩的直接证据：各类计数之和必等于样本外点数
        Map<MarketRegime, Integer> actual = r.actualRegimeCounts();
        Map<MarketRegime, Integer> predicted = r.predictedRegimeCounts();
        assertThat(actual.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(r.testPoints());
        assertThat(predicted.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(r.testPoints());
    }

    @Test
    void volBaselinesOnlyUseCompletedHorizonReturns() {
        List<Integer> decisionIndexes = List.of(0, 1, 2, 3, 4, 5, 6);

        assertThat(MultiOutputEvalService.knownRealizedReturnEndExclusive(decisionIndexes, 0, 3)).isZero();
        assertThat(MultiOutputEvalService.knownRealizedReturnEndExclusive(decisionIndexes, 2, 3)).isZero();
        assertThat(MultiOutputEvalService.knownRealizedReturnEndExclusive(decisionIndexes, 3, 3)).isEqualTo(1);
        assertThat(MultiOutputEvalService.knownRealizedReturnEndExclusive(decisionIndexes, 5, 3)).isEqualTo(3);
    }

    @Test
    void reportCarriesDirectionReturnSeriesForDeflatedSharpe() {
        EvalParams params = new EvalParams(1.5, 0.94, 2, 0, 2, 100, 0.95, 42L);

        MultiOutputReport r = MultiOutputEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(8 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                QuantCoreForecaster.defaults(ForecastHorizon.H6), params);

        // DSR 钩子：方向逐期收益序列须与样本外点数一致、年化周期取 horizon 口径，供 multi-*.json 离线重判
        assertThat(r.directionReturnSeries()).isNotNull();
        assertThat(r.directionReturnSeries().periodReturns()).hasSize(r.testPoints());
        assertThat(r.directionReturnSeries().periodsPerYear()).isEqualTo(ForecastHorizon.H6.periodsPerYear());
    }

    @Test
    void volBeatsAllBaselinesRequiresSignificantQlikeEdgeNotJustLowerPointEstimate() {
        EvalParams params = new EvalParams(1.5, 0.94, 5, 0, 30, 100, 0.95, 42L);

        MultiOutputReport r = MultiOutputEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(40 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                QuantCoreForecaster.defaults(ForecastHorizon.H6), params);

        // 新口径：跑赢全部基准 == 对最难基准的 QLIKE 优势在 DM/Newey-West 检验下显著；裸点估计更低/打平不算赢
        assertThat(r.volBeatsAllBaselines())
                .isEqualTo(r.volQlikeVsBestBaseline().modelBetterAt(0.05));
    }

    @Test
    void summaryLeadsRegimeLineWithBalancedAccuracySkillVerdict() {
        EvalParams params = new EvalParams(1.5, 0.94, 5, 0, 30, 100, 0.95, 42L);

        MultiOutputReport r = MultiOutputEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(40 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                QuantCoreForecaster.defaults(ForecastHorizon.H6), params);

        String summary = r.summary(); // 同时验证 format 参数对齐不抛异常
        assertThat(summary).contains("balAcc=", "chance=", "macroF1=");
        assertThat(summary).containsAnyOf("有技能", "无技能");
        assertThat(summary).contains("次:准确率="); // 裸准确率/persistence 降级为次要口径
    }

    static List<KlineBar> uptrend1m(int count) {
        List<KlineBar> bars = new ArrayList<>(count);
        double price = 100.0;
        for (int i = 0; i < count; i++) {
            long t = i * 60_000L;
            BigDecimal o = BigDecimal.valueOf(price);
            BigDecimal c = BigDecimal.valueOf(price + 0.1);
            BigDecimal hi = BigDecimal.valueOf(price + 0.15);
            BigDecimal lo = BigDecimal.valueOf(price - 0.05);
            bars.add(new KlineBar(t, t + 59_999L, o, hi, lo, c, BigDecimal.ONE));
            price += 0.1;
        }
        return bars;
    }
}
