package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.factor.ContinuousFactorVector;
import com.mawai.wiibservice.agent.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibservice.agent.research.forecast.Forecast;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.forecast.HorizonScaledVolForecaster;
import com.mawai.wiibservice.agent.research.forecast.MultiFactorForecaster;
import com.mawai.wiibservice.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibservice.agent.research.forecast.TrainingSample;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.series.MarketSeriesPoint;
import com.mawai.wiibservice.agent.research.stats.VolatilityEstimator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

class ResearchEvalServiceTest {

    @Test
    void evaluateBarsProducesMultiStrategyOutOfSampleReport() {
        // 小样本验证"多策略同框编排"：MultiFactor 在 <30 根决策 bar 下暖机 flat（不影响编排验证）
        EvalParams params = new EvalParams(1.5, 0.94, 3, 0, 2, 200, 0.95, 42L);
        List<KlineBar> oneMin = uptrend1m(8 * 360); // 8 个 6h 桶
        List<Forecaster> fcs = List.of(new EwmaMomentumForecaster(2, 4), MultiFactorForecaster.defaults());

        ComparisonReport r = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, oneMin, List.of(), List.of(), List.of(), List.of(), fcs, params);

        assertThat(r).isNotNull();
        assertThat(r.symbol()).isEqualTo("BTCUSDT");
        assertThat(r.horizonHours()).isEqualTo(6);
        assertThat(r.testPoints()).isGreaterThan(0);
        assertThat(r.buyAndHoldReturn().doubleValue()).isGreaterThan(0); // 上行趋势 buy&hold 为正
        assertThat(r.strategies()).hasSize(2);
        assertThat(r.strategies()).extracting(StrategyLine::name)
                .containsExactly("ewma_momentum_2_4", "multi_factor_trend_funding_fng");
        for (StrategyLine s : r.strategies()) {
            assertThat(s.metrics().periods()).isEqualTo(r.testPoints()); // 每策略 periods 与 test 点数一致
            assertThat(s.naivePercentile()).isBetween(0.0, 1.0);
        }
        assertThat(r.summary()).contains("BTCUSDT").contains("buy&hold");
    }

    @Test
    void offChainSeriesAreAsOfAlignedAndAffectMultiFactorDirection() {
        // 足够长上涨样本(趋势腿=+1) + 全程极端正资金费 + 极贪 → off-chain 应把 MultiFactor 压成做空，与趋势相反。
        // 证明 fundingSeries/fearGreedSeries 经 SeriesAligner as-of 真装配进了预测（链下"穿过尺子"）。
        EvalParams params = new EvalParams(1.5, 0.94, 5, 0, 30, 100, 0.95, 42L);
        List<KlineBar> oneMin = uptrend1m(40 * 360); // 40 个 H6 桶 → 决策点 subList ≥30，ma_alignment 可算
        // 单点序列(ts=0)：任何决策点 as-of 都取到它（floor）
        List<MarketSeriesPoint> funding = List.of(new MarketSeriesPoint(0L, BigDecimal.valueOf(0.002)));  // 极端正→偏空
        List<MarketSeriesPoint> fearGreed = List.of(new MarketSeriesPoint(0L, BigDecimal.valueOf(95)));   // 极贪→偏空

        ComparisonReport r = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, oneMin, funding, fearGreed, List.of(), List.of(),
                List.of(new EwmaMomentumForecaster(2, 4), MultiFactorForecaster.defaults()), params);

        StrategyLine ewma = r.strategies().get(0);
        StrategyLine multi = r.strategies().get(1);
        // 上涨中：EWMA 顺势做多→收益>0；MultiFactor 被 off-chain 压成做空→收益<0
        assertThat(ewma.strategyReturn().doubleValue()).isGreaterThan(0);
        assertThat(multi.strategyReturn().doubleValue()).isLessThan(0);
    }

    @Test
    void onChainSeriesAreAsOfAlignedAndDriveOnChainForecaster() {
        // 上涨样本 + 全程 ETF 净流入 + 稳定币铸币 → onChainOnly 应顺势做多 → 收益>0。
        // 证明 etfFlow/stablecoin 序列经 SeriesAligner as-of 真装配进了预测（链上"穿过尺子"）。
        EvalParams params = new EvalParams(1.5, 0.94, 5, 0, 30, 100, 0.95, 42L);
        List<KlineBar> oneMin = uptrend1m(40 * 360);
        List<MarketSeriesPoint> etf = List.of(new MarketSeriesPoint(0L, BigDecimal.valueOf(150)));               // 净流入→偏多
        List<MarketSeriesPoint> stablecoin = List.of(new MarketSeriesPoint(0L, BigDecimal.valueOf(500_000_000L))); // 铸币→偏多

        ComparisonReport r = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, oneMin, List.of(), List.of(), etf, stablecoin,
                List.of(MultiFactorForecaster.onChainOnly()), params);

        StrategyLine oc = r.strategies().get(0);
        assertThat(oc.name()).isEqualTo("onchain_etf_stablecoin");
        assertThat(oc.strategyReturn().doubleValue()).isGreaterThan(0);   // 上涨中做多→正收益
    }

    @Test
    void evaluateBarsUsesTripleBarrierPathBeforeCloseToCloseReturn() {
        // 决策后下一根 H6 最终收涨，但路径先打到下栏；做多应按三隔栏止损亏损，而不是按收盘收益盈利。
        EvalParams params = new EvalParams(1.0, 0.94, 1, 0, 2, 100, 0.95, 42L);
        AssembledPoints a = ResearchEvalService.assemblePoints(
                ForecastHorizon.H6, pathHitsLowerThenClosesUp(), List.of(),
                List.of(), List.of(), List.of(), List.of(), params,
                ResearchEvalService.FEATURE_LOOKBACK_BARS);

        long bucket3CloseTime = 4 * 360L * 60_000L - 1L;
        int point = -1;
        for (int i = 0; i < a.decisionBars().size(); i++) {
            if (a.decisionBars().get(i).closeTime() == bucket3CloseTime) {
                point = i;
                break;
            }
        }

        assertThat(point).isGreaterThanOrEqualTo(0);
        assertThat(a.pathOutcomesByPoint().get(point).actualChangeBps()).isGreaterThan(0); // close-to-close 是盈利
        assertThat(a.longBarrierReturnsByPoint().get(point).doubleValue()).isLessThan(0);  // 但路径先触下栏
    }

    @Test
    void compoundReturnKeepsReportScaleBounded() {
        List<BigDecimal> returns = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            returns.add(new BigDecimal("0.001"));
        }

        BigDecimal compounded = ResearchEvalService.compound(returns);

        assertThat(compounded.scale()).isLessThanOrEqualTo(10);
        assertThat(compounded.toPlainString()).hasSizeLessThan(24);
        assertThat(compounded.doubleValue()).isCloseTo(Math.pow(1.001, 2_000) - 1.0, within(1e-9));
    }

    @Test
    void evaluateBarsFitsForecasterOnEachTrainWindowBeforeForecastingTestWindow() {
        EvalParams params = new EvalParams(1.5, 0.94, 2, 0, 2, 50, 0.95, 42L);
        RecordingFitForecaster fc = new RecordingFitForecaster();

        ComparisonReport r = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(8 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(fc), params);

        assertThat(r.testPoints()).isGreaterThan(4);
        assertThat(fc.fitSizes.subList(0, 2)).containsExactly(2, 4);
        assertThat(fc.forecastDecisionCloses).hasSize(r.testPoints());
        for (int i = 0; i < fc.forecastDecisionCloses.size(); i++) {
            assertThat(fc.trainLastClosesAtForecast.get(i)).isLessThan(fc.forecastDecisionCloses.get(i));
        }
    }

    @Test
    void evaluateBarsPassesContinuousFactorVectorIntoForecastFeatures() {
        EvalParams params = new EvalParams(1.5, 0.94, 2, 0, 30, 50, 0.95, 42L);
        CapturingFactorForecaster fc = new CapturingFactorForecaster();

        ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(40 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(fc), params);

        assertThat(fc.factors).isNotEmpty();
        ContinuousFactorVector v = fc.factors.stream()
                .filter(f -> f.riskAdjustedMomentum() > 0.0)
                .findFirst()
                .orElseThrow();
        assertThat(v.riskAdjustedMomentum()).isGreaterThan(0.0);
        assertThat(v.shortReversal()).isLessThan(0.0);
        assertThat(v.amihudIlliquidity()).isGreaterThan(0.0);
        assertThat(v.residualMomentum()).isZero(); // 单标的评估未接 benchmark，残差腿先中性。
    }

    @Test
    void evaluateBarsUsesBenchmarkReturnsForResidualMomentum() {
        EvalParams params = new EvalParams(1.5, 0.94, 2, 0, 30, 50, 0.95, 42L);
        CapturingFactorForecaster fc = new CapturingFactorForecaster();

        ResearchEvalService.evaluateBars(
                "ETHUSDT", ForecastHorizon.H6, uptrend1m(40 * 360), flat1m(40 * 360, 100.0),
                List.of(), List.of(), List.of(), List.of(), List.of(fc), params);

        assertThat(fc.factors).isNotEmpty();
        assertThat(fc.factors.stream().anyMatch(f -> f.residualMomentum() > 0.0)).isTrue();
    }

    @Test
    void benchmarkFutureRowsDoNotChangeCurrentResidualMomentum() {
        EvalParams params = new EvalParams(1.5, 0.94, 2, 0, 30, 50, 0.95, 42L);
        List<KlineBar> asset = uptrend1m(40 * 360);
        List<KlineBar> benchmark = flat1m(40 * 360, 100.0);
        List<KlineBar> changedFuture = replaceFromMinute(benchmark, 35 * 360, 10_000.0);
        CapturingFactorForecaster base = new CapturingFactorForecaster();
        CapturingFactorForecaster changed = new CapturingFactorForecaster();

        ResearchEvalService.evaluateBars(
                "ETHUSDT", ForecastHorizon.H6, asset, benchmark,
                List.of(), List.of(), List.of(), List.of(), List.of(base), params);
        ResearchEvalService.evaluateBars(
                "ETHUSDT", ForecastHorizon.H6, asset, changedFuture,
                List.of(), List.of(), List.of(), List.of(), List.of(changed), params);

        assertThat(changed.factors.get(0).residualMomentum())
                .isCloseTo(base.factors.get(0).residualMomentum(), within(1e-12));
    }

    @Test
    void evaluateBarsUsesFiveMinuteFeatureBarsWithBoundedLookback() {
        EvalParams params = new EvalParams(1.5, 0.94, 2, 0, 30, 50, 0.95, 42L);
        CapturingFeatureForecaster fc = new CapturingFeatureForecaster();

        ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(40 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(fc), params);

        assertThat(fc.barMillis).isNotEmpty().allMatch(v -> v == ResearchEvalService.FEATURE_BAR_MILLIS);
        assertThat(fc.featureSizes).isNotEmpty().allMatch(v -> v <= ResearchEvalService.FEATURE_LOOKBACK_BARS);
        assertThat(fc.featureSizes).allMatch(v -> v > 30); // regime/indicator 暖机仍有足够 5m bar
    }

    @Test
    void horizonIsFutureHoldWindowNotDecisionBarInterval() {
        EvalParams params = new EvalParams(1.5, 0.94, 2, 0, 30, 50, 0.95, 42L);

        AssembledPoints a = ResearchEvalService.assemblePoints(
                ForecastHorizon.H6, uptrend1m(8 * 360), List.of(),
                List.of(), List.of(), List.of(), List.of(), params,
                ResearchEvalService.FEATURE_LOOKBACK_BARS);

        assertThat(a.horizonDecisionBars()).isEqualTo(72); // 6h / 5m
        assertThat(a.points()).isEqualTo(8 * 72 - 72);
        assertThat(a.decisionBars().get(1).openTime() - a.decisionBars().get(0).openTime())
                .isEqualTo(ResearchEvalService.FEATURE_BAR_MILLIS);
        assertThat(a.baselineSigmaByPoint()).hasSize(a.points());
        double expectedSigma = HorizonScaledVolForecaster.scale(
                VolatilityEstimator.ewmaVolatility(
                        a.featuresByPoint().get(0).barsUpToNow(), params.lambda()),
                a.featuresByPoint().get(0), ForecastHorizon.H6);
        assertThat(a.baselineSigmaByPoint()[0]).isCloseTo(expectedSigma, within(1e-12));
    }

    @Test
    void horizonDecisionBarsRequiresHorizonGreaterThanDecisionInterval() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResearchEvalService.horizonDecisionBars(
                        ForecastHorizon.H6, ForecastHorizon.H6.millis()))
                .withMessageContaining("horizon 必须大于决策间隔");
    }

    @Test
    void assemblePointsCanUseFifteenMinuteDecisionBars() {
        EvalParams params = new EvalParams(1.5, 0.94, 2, 0, 30, 50, 0.95, 42L);

        AssembledPoints a = ResearchEvalService.assemblePoints(
                ForecastHorizon.H6, uptrend1m(8 * 360), List.of(),
                List.of(), List.of(), List.of(), List.of(), params,
                ResearchEvalService.FEATURE_LOOKBACK_BARS,
                ResearchEvalService.FIFTEEN_MINUTE_BAR_MILLIS);

        assertThat(a.horizonDecisionBars()).isEqualTo(24); // 6h / 15m
        assertThat(a.points()).isEqualTo(8 * 24 - 24);
        assertThat(a.decisionBars().get(1).openTime() - a.decisionBars().get(0).openTime())
                .isEqualTo(ResearchEvalService.FIFTEEN_MINUTE_BAR_MILLIS);
    }

    /** 慢/快双正弦波 1m 序列：6h 聚合后均线交叉、ma_alignment 在 ±1/0 翻转、EWMA 方向随之变化，
     *  足以让"窗口 vs 全历史"产生可观测差异(若有)。价格恒正。 */
    static List<KlineBar> wavy1m(int count) {
        List<KlineBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long t = i * 60_000L;
            double price = 1000.0 + 300.0 * Math.sin(i / 5000.0) + 50.0 * Math.sin(i / 777.0);
            double next = 1000.0 + 300.0 * Math.sin((i + 1) / 5000.0) + 50.0 * Math.sin((i + 1) / 777.0);
            BigDecimal o = BigDecimal.valueOf(price);
            BigDecimal c = BigDecimal.valueOf(next);
            BigDecimal hi = BigDecimal.valueOf(Math.max(price, next) + 0.5);
            BigDecimal lo = BigDecimal.valueOf(Math.min(price, next) - 0.5);
            bars.add(new KlineBar(t, t + 59_999L, o, hi, lo, c, BigDecimal.ONE));
        }
        return bars;
    }

    /** 生成 count 根 1m bar，价格每根 +0.1，时间从 0 起每根 +60_000ms。 */
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

    static List<KlineBar> flat1m(int count, double price) {
        List<KlineBar> bars = new ArrayList<>(count);
        BigDecimal p = BigDecimal.valueOf(price);
        for (int i = 0; i < count; i++) {
            long t = i * 60_000L;
            bars.add(new KlineBar(t, t + 59_999L, p, p, p, p, BigDecimal.ONE));
        }
        return bars;
    }

    static List<KlineBar> replaceFromMinute(List<KlineBar> source, int startMinute, double price) {
        List<KlineBar> out = new ArrayList<>(source.size());
        BigDecimal p = BigDecimal.valueOf(price);
        for (int i = 0; i < source.size(); i++) {
            KlineBar b = source.get(i);
            out.add(i < startMinute ? b : new KlineBar(
                    b.openTime(), b.closeTime(), p, p, p, p, b.volume()));
        }
        return out;
    }

    static List<KlineBar> pathHitsLowerThenClosesUp() {
        List<KlineBar> bars = new ArrayList<>(5 * 360);
        appendBucket(bars, 0, 100.0, 100.0);
        appendBucket(bars, 1, 100.0, 102.0);
        appendBucket(bars, 2, 102.0, 99.0);
        appendBucket(bars, 3, 99.0, 100.0);      // test 决策点 entry=100
        appendStopThenRallyBucket(bars, 4);       // 先 low=90 触下栏，再收到 110
        return bars;
    }

    static void appendBucket(List<KlineBar> bars, int bucket, double start, double end) {
        int minutes = 360;
        for (int m = 0; m < minutes; m++) {
            double p0 = start + (end - start) * m / minutes;
            double p1 = start + (end - start) * (m + 1) / minutes;
            addBar(bars, bucket, m, p0, Math.max(p0, p1) + 0.01, Math.min(p0, p1) - 0.01, p1);
        }
    }

    static void appendStopThenRallyBucket(List<KlineBar> bars, int bucket) {
        addBar(bars, bucket, 0, 100.0, 100.2, 1.0, 99.0);
        for (int m = 1; m < 360; m++) {
            double p0 = 99.0 + (110.0 - 99.0) * (m - 1) / 359;
            double p1 = 99.0 + (110.0 - 99.0) * m / 359;
            addBar(bars, bucket, m, p0, Math.max(p0, p1) + 0.01, Math.min(p0, p1) - 0.01, p1);
        }
    }

    static void addBar(List<KlineBar> bars, int bucket, int minute, double open, double high, double low, double close) {
        long t = (bucket * 360L + minute) * 60_000L;
        bars.add(new KlineBar(t, t + 59_999L,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high), BigDecimal.valueOf(low),
                BigDecimal.valueOf(close), BigDecimal.ONE));
    }

    private static final class RecordingFitForecaster implements Forecaster {
        private final List<Integer> fitSizes = new ArrayList<>();
        private final List<Long> trainLastClosesAtForecast = new ArrayList<>();
        private final List<Long> forecastDecisionCloses = new ArrayList<>();
        private long currentTrainLastClose = Long.MIN_VALUE;

        @Override
        public Forecaster fit(List<TrainingSample> trainSamples) {
            fitSizes.add(trainSamples.size());
            ResearchFeatures last = trainSamples.get(trainSamples.size() - 1).features();
            currentTrainLastClose = lastCloseTime(last);
            return this;
        }

        @Override
        public Forecast forecast(ResearchFeatures features) {
            trainLastClosesAtForecast.add(currentTrainLastClose);
            forecastDecisionCloses.add(lastCloseTime(features));
            return new Forecast(1, 1.0);
        }

        @Override
        public String name() {
            return "recording_fit";
        }

        private static long lastCloseTime(ResearchFeatures features) {
            List<KlineBar> bars = features.barsUpToNow();
            return bars.get(bars.size() - 1).closeTime();
        }
    }

    private static final class CapturingFactorForecaster implements Forecaster {
        private final List<ContinuousFactorVector> factors = new ArrayList<>();

        @Override
        public Forecast forecast(ResearchFeatures features) {
            factors.add(features.continuousFactors());
            return Forecast.flat();
        }

        @Override
        public String name() {
            return "capture_factors";
        }
    }

    private static final class CapturingFeatureForecaster implements Forecaster {
        private final List<Integer> featureSizes = new ArrayList<>();
        private final List<Long> barMillis = new ArrayList<>();

        @Override
        public Forecast forecast(ResearchFeatures features) {
            featureSizes.add(features.barsUpToNow().size());
            barMillis.add(features.inferredBarMillis());
            return Forecast.flat();
        }

        @Override
        public String name() {
            return "capture_features";
        }
    }
}
