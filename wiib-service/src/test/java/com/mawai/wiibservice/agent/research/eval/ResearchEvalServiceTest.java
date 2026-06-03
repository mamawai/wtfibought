package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibservice.agent.research.forecast.Forecast;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.forecast.MultiFactorForecaster;
import com.mawai.wiibservice.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibservice.agent.research.forecast.TrainingSample;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.series.MarketSeriesPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ResearchEvalServiceTest {

    @Test
    void evaluateBarsProducesMultiStrategyOutOfSampleReport() {
        // 小样本验证"多策略同框编排"：MultiFactor 在 <30 H-bar 下暖机 flat（不影响编排验证）
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
        Forecaster alwaysLong = new Forecaster() {
            @Override
            public Forecast forecast(ResearchFeatures features) {
                return new Forecast(1, 1.0);
            }

            @Override
            public String name() {
                return "always_long";
            }
        };

        ComparisonReport r = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, pathHitsLowerThenClosesUp(),
                List.of(), List.of(), List.of(), List.of(), List.of(alwaysLong), params);

        StrategyLine s = r.strategies().get(0);
        assertThat(r.buyAndHoldReturn().doubleValue()).isGreaterThan(0);   // close-to-close 是盈利
        assertThat(s.strategyReturn().doubleValue()).isLessThan(0);        // 三隔栏先触下栏，做多亏损
        assertThat(s.returnSeries().periodReturns().get(0).doubleValue()).isLessThan(0);
    }

    @Test
    void evaluateBarsFitsForecasterOnEachTrainWindowBeforeForecastingTestWindow() {
        EvalParams params = new EvalParams(1.5, 0.94, 2, 0, 2, 50, 0.95, 42L);
        RecordingFitForecaster fc = new RecordingFitForecaster();

        ComparisonReport r = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, uptrend1m(8 * 360),
                List.of(), List.of(), List.of(), List.of(), List.of(fc), params);

        assertThat(r.testPoints()).isEqualTo(4);
        assertThat(fc.fitSizes).containsExactly(2, 4);
        assertThat(fc.forecastDecisionCloses).hasSize(4);
        for (int i = 0; i < fc.forecastDecisionCloses.size(); i++) {
            assertThat(fc.trainLastClosesAtForecast.get(i)).isLessThan(fc.forecastDecisionCloses.get(i));
        }
    }

    @Test
    void featureLookbackWindowMatchesFullHistoryOnWavyFixture() {
        // 回归守卫：同一批 bar 跑 W=256(默认) 与 W=MAX(全历史)，逐策略逐期收益在现有研究口径下保持一致。
        // 决策点须 >256 才让窗口对晚期点真正生效：320 个 6h 桶 → 319 决策点，样本外块覆盖到 i≥256。
        EvalParams params = EvalParams.defaults();
        List<KlineBar> oneMin = wavy1m(320 * 360);
        List<Forecaster> fcs = List.of(
                new EwmaMomentumForecaster(12, 26),   // 走 Ema(整段) 路径
                MultiFactorForecaster.defaults());     // 走 calcAll→ma_alignment 路径（主成本）

        ComparisonReport windowed = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, oneMin, List.of(), List.of(), List.of(), List.of(), fcs, params);
        ComparisonReport full = ResearchEvalService.evaluateBars(
                "BTCUSDT", ForecastHorizon.H6, oneMin, List.of(), List.of(), List.of(), List.of(), fcs, params, Integer.MAX_VALUE);

        assertThat(windowed.testPoints()).isEqualTo(full.testPoints());
        assertThat(windowed.buyAndHoldReturn()).isEqualByComparingTo(full.buyAndHoldReturn());
        for (int s = 0; s < fcs.size(); s++) {
            StrategyLine w = windowed.strategies().get(s);
            StrategyLine f = full.strategies().get(s);
            assertThat(w.name()).isEqualTo(f.name());
            List<BigDecimal> wr = w.returnSeries().periodReturns();
            List<BigDecimal> fr = f.returnSeries().periodReturns();
            assertThat(wr).hasSameSizeAs(fr);
            // 逐期收益逐点比对：方向若被窗口改变会整符号翻转(差≈2×|r|)，1e-6 容差能抓到实际漂移。
            for (int j = 0; j < wr.size(); j++) {
                assertThat(wr.get(j).doubleValue())
                        .as("%s 第%d期收益", w.name(), j)
                        .isCloseTo(fr.get(j).doubleValue(), within(1e-6));
            }
            assertThat(w.metrics().annualizedSharpe()).isCloseTo(f.metrics().annualizedSharpe(), within(1e-6));
            assertThat(w.strategyReturn().doubleValue()).isCloseTo(f.strategyReturn().doubleValue(), within(1e-6));
        }
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
        addBar(bars, bucket, 0, 100.0, 100.2, 90.0, 99.0);
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
}
