package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.benchmark.BenchmarkCalculator;
import com.mawai.wiibservice.agent.research.factor.ContinuousFactorBuilder;
import com.mawai.wiibservice.agent.research.factor.ContinuousFactorParams;
import com.mawai.wiibservice.agent.research.factor.ContinuousFactorVector;
import com.mawai.wiibservice.agent.research.factor.FactorMath;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.forecast.HorizonScaledVolForecaster;
import com.mawai.wiibservice.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibservice.agent.research.forecast.TrainingSample;
import com.mawai.wiibservice.agent.research.kline.KlineAggregator;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import com.mawai.wiibservice.agent.research.label.BarrierLabel;
import com.mawai.wiibservice.agent.research.label.RegimeLabeler;
import com.mawai.wiibservice.agent.research.label.TripleBarrierLabeler;
import com.mawai.wiibservice.agent.research.metrics.ReturnSeries;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;
import com.mawai.wiibservice.agent.research.series.MarketSeriesPoint;
import com.mawai.wiibservice.agent.research.series.MarketSeriesStore;
import com.mawai.wiibservice.agent.research.series.SeriesAligner;
import com.mawai.wiibservice.agent.research.series.SeriesCode;
import com.mawai.wiibservice.agent.research.stats.VolatilityEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 评估编排：从库加载基础 K线 + 链下序列 → 聚合 5m/15m 决策 bar → 调纯核心 evaluateBars → 写 target/ JSON。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchEvalService {

    private static final BigDecimal NEUTRAL_FUNDING = BigDecimal.ZERO;            // 链下缺口中性：资金费=0
    private static final BigDecimal NEUTRAL_FEAR_GREED = BigDecimal.valueOf(50);  // 恐惧贪婪=50（中性）
    private static final BigDecimal NEUTRAL_ONCHAIN = BigDecimal.ZERO;            // 链上缺口中性：ETF 流入/稳定币差=0

    private static final String KLINE_INTERVAL = KlineHistoryStore.DEFAULT_INTERVAL;
    // 默认每 5m 出一个预测；6/12/24h 只作为未来持仓验证窗口，不是 K线粒度。
    static final long FEATURE_BAR_MILLIS = 5 * 60_000L;
    static final long FIFTEEN_MINUTE_BAR_MILLIS = 15 * 60_000L;
    private static final long FEATURE_LOOKBACK_MILLIS = Duration.ofHours(48).toMillis();
    // 默认 5m 回看 48h；15m runner 会通过 featureLookbackBars(decisionBarMillis) 换算成 192 根。
    static final int FEATURE_LOOKBACK_BARS = barsForDuration(FEATURE_LOOKBACK_MILLIS, FEATURE_BAR_MILLIS);
    private static final String BENCHMARK_SYMBOL = "BTCUSDT";
    private static final double[] EMPTY_BENCHMARK_RETURNS = new double[0];
    private static final MathContext COMPOUND_CONTEXT = new MathContext(18, RoundingMode.HALF_UP);
    private static final int REPORT_RETURN_SCALE = 10;

    private final KlineHistoryStore store;
    private final MarketSeriesStore seriesStore;

    /** 一窗多预测器同框评估。链下/链上序列(funding/fng/etf/stablecoin)随基础 K 线按 [fromMs,toMs) 加载，逐决策点 as-of 对齐。 */
    public ComparisonReport evaluate(String symbol, ForecastHorizon horizon, long fromMs, long toMs,
                                     List<Forecaster> forecasters, EvalParams params) {
        List<KlineBar> baseBars = store.load(symbol, KLINE_INTERVAL, fromMs, toMs);
        List<KlineBar> benchmarkBaseBars = BENCHMARK_SYMBOL.equalsIgnoreCase(symbol)
                ? List.of()
                : store.load(BENCHMARK_SYMBOL, KLINE_INTERVAL, fromMs, toMs);
        List<MarketSeriesPoint> funding = seriesStore.load(symbol, SeriesCode.FUNDING, fromMs, toMs);
        List<MarketSeriesPoint> fearGreed = seriesStore.load(MarketSeriesStore.GLOBAL, SeriesCode.FEAR_GREED, fromMs, toMs);
        List<MarketSeriesPoint> etfFlow = seriesStore.load(symbol, SeriesCode.ETF_FLOW, fromMs, toMs);            // 仅 BTC 有；其他 symbol 空→中性
        List<MarketSeriesPoint> stablecoin = seriesStore.load(symbol, SeriesCode.STABLECOIN_DELTA, fromMs, toMs);
        ComparisonReport report = evaluateBars(symbol, horizon, baseBars, benchmarkBaseBars,
                funding, fearGreed, etfFlow, stablecoin, forecasters, params);
        writeReport(report);
        return report;
    }

    /**
     * 纯核心（无 DB，可单测）：聚合→walk-forward 取样本外→逐决策点装配 point-in-time 特征→
     * 每预测器模拟收益+指标→buy&hold 基准算一次→多策略同框报告。
     * 各序列须按 ts 升序（store.load 已保证）；可为空→该因子全程中性。
     */
    public static ComparisonReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                                List<MarketSeriesPoint> fundingSeries,
                                                List<MarketSeriesPoint> fearGreedSeries,
                                                List<MarketSeriesPoint> etfFlowSeries,
                                                List<MarketSeriesPoint> stablecoinSeries,
                                                List<Forecaster> forecasters, EvalParams params) {
        return evaluateBars(symbol, horizon, oneMin, List.of(), fundingSeries, fearGreedSeries, etfFlowSeries,
                stablecoinSeries, forecasters, params, FEATURE_LOOKBACK_BARS);
    }

    /** 同上；benchmarkOneMin 用于残差动量，传空则残差腿中性。 */
    public static ComparisonReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                                List<KlineBar> benchmarkOneMin,
                                                List<MarketSeriesPoint> fundingSeries,
                                                List<MarketSeriesPoint> fearGreedSeries,
                                                List<MarketSeriesPoint> etfFlowSeries,
                                                List<MarketSeriesPoint> stablecoinSeries,
                                                List<Forecaster> forecasters, EvalParams params) {
        return evaluateBars(symbol, horizon, oneMin, benchmarkOneMin, fundingSeries, fearGreedSeries, etfFlowSeries,
                stablecoinSeries, forecasters, params, FEATURE_LOOKBACK_BARS);
    }

    /** 同上；回看窗 W 可注入——测试可传 Integer.MAX_VALUE 跑"全历史"对照。生产恒走默认 W。 */
    static ComparisonReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                         List<MarketSeriesPoint> fundingSeries,
                                         List<MarketSeriesPoint> fearGreedSeries,
                                         List<MarketSeriesPoint> etfFlowSeries,
                                         List<MarketSeriesPoint> stablecoinSeries,
                                         List<Forecaster> forecasters, EvalParams params, int featureLookbackBars) {
        return evaluateBars(symbol, horizon, oneMin, List.of(), fundingSeries, fearGreedSeries, etfFlowSeries,
                stablecoinSeries, forecasters, params, featureLookbackBars);
    }

    /** 同上；带 benchmark 的测试/runner 入口。 */
    static ComparisonReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                         List<KlineBar> benchmarkOneMin,
                                         List<MarketSeriesPoint> fundingSeries,
                                         List<MarketSeriesPoint> fearGreedSeries,
                                         List<MarketSeriesPoint> etfFlowSeries,
                                         List<MarketSeriesPoint> stablecoinSeries,
                                         List<Forecaster> forecasters, EvalParams params, int featureLookbackBars) {
        return evaluateBars(symbol, horizon, oneMin, benchmarkOneMin, fundingSeries, fearGreedSeries,
                etfFlowSeries, stablecoinSeries, forecasters, params, featureLookbackBars, FEATURE_BAR_MILLIS);
    }

    /** 同上；decisionBarMillis 只允许 5m/15m，horizon 仍固定为未来 6/12/24h。 */
    static ComparisonReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                         List<KlineBar> benchmarkOneMin,
                                         List<MarketSeriesPoint> fundingSeries,
                                         List<MarketSeriesPoint> fearGreedSeries,
                                         List<MarketSeriesPoint> etfFlowSeries,
                                         List<MarketSeriesPoint> stablecoinSeries,
                                         List<Forecaster> forecasters, EvalParams params,
                                         int featureLookbackBars, long decisionBarMillis) {
        AssembledPoints assembled = assemblePoints(horizon, oneMin, benchmarkOneMin,
                fundingSeries, fearGreedSeries, etfFlowSeries, stablecoinSeries,
                params, featureLookbackBars, decisionBarMillis);
        List<KlineBar> decisionBars = assembled.decisionBars();
        int points = assembled.points();
        List<ResearchFeatures> featuresByPoint = assembled.featuresByPoint();
        List<BigDecimal> longBarrierReturnsByPoint = assembled.longBarrierReturnsByPoint();
        List<TrainingSample> samplesByPoint = assembled.samplesByPoint();

        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(
                points, params.testSize(), assembled.horizonDecisionBars(), params.embargoBars(), params.minTrain());

        int firstDecisionBarIndex = -1, lastExitBarIndex = -1, testPointCount = 0;
        for (WalkForwardWindow w : wins) {
            if (firstDecisionBarIndex < 0) {
                firstDecisionBarIndex = assembled.decisionBarIndexes().get(w.testStart());
            }
            int lastTestPoint = w.testEnd() - 1;
            lastExitBarIndex = Math.max(lastExitBarIndex,
                    assembled.decisionBarIndexes().get(lastTestPoint) + assembled.horizonDecisionBars());
            testPointCount += w.testSize();
        }

        // ---- buy&hold：从首个 OOS 决策 close 持有到最后一个 OOS 信号的 horizon 退出点 ----
        BigDecimal buyHold = (firstDecisionBarIndex >= 0 && lastExitBarIndex < assembled.decisionIntervalBars().size())
                ? BenchmarkCalculator.buyAndHoldReturn(
                assembled.decisionIntervalBars().subList(firstDecisionBarIndex, lastExitBarIndex + 1))
                : BigDecimal.ZERO;

        // ---- 每预测器一条 StrategyLine（naive 分位依赖各自 positions，逐策略算） ----
        List<StrategyLine> lines = new ArrayList<>(forecasters.size());
        for (Forecaster fc : forecasters) {
            List<Integer> positions = new ArrayList<>(testPointCount);
            List<BigDecimal> posReturns = new ArrayList<>(testPointCount);
            List<BigDecimal> testLongReturns = new ArrayList<>(testPointCount);
            for (WalkForwardWindow w : wins) {
                // 每个窗口单独 fit；训练样本只来自 testStart 之前，且中间已有 purge+embargo 间隔。
                Forecaster trained = fc.fit(List.copyOf(samplesByPoint.subList(w.trainStart(), w.trainEnd())));
                for (int i = w.testStart(); i < w.testEnd(); i++) {
                    BigDecimal longReturn = longBarrierReturnsByPoint.get(i);
                    int dir = trained.forecast(featuresByPoint.get(i)).direction();
                    positions.add(dir);
                    testLongReturns.add(longReturn);
                    posReturns.add(longReturn.multiply(BigDecimal.valueOf(dir)));
                }
            }
            ReturnSeries series = new ReturnSeries(fc.name(), posReturns, horizon.periodsPerYear());
            RiskAdjustedMetrics metrics = RiskAdjustedMetrics.from(series);
            double naivePct = positions.isEmpty() ? 0.0
                    : BenchmarkCalculator.permutationPercentile(positions, testLongReturns, params.iterations(), params.seed());
            BigDecimal stratReturn = compound(posReturns);
            lines.add(new StrategyLine(fc.name(), metrics, stratReturn,
                    stratReturn.compareTo(buyHold) > 0, naivePct, naivePct >= params.naivePercentileThreshold(), series));
        }

        return new ComparisonReport(symbol, horizon.hours(), minutes(assembled.decisionBarMillis()),
                points, testPointCount, buyHold, lines);
    }

    /**
     * 逐决策点 point-in-time 装配（单/多输出评估共用，保证同一份无泄漏口径）：
     * 基础 K 线 → 5m/15m feature bars；按 horizon 间隔采决策点；每点 as-of 链下 + continuous 因子（绝不含未来）+ 未来路径目标。
     */
    static AssembledPoints assemblePoints(ForecastHorizon horizon, List<KlineBar> oneMin,
                                          List<KlineBar> benchmarkOneMin,
                                          List<MarketSeriesPoint> fundingSeries,
                                          List<MarketSeriesPoint> fearGreedSeries,
                                          List<MarketSeriesPoint> etfFlowSeries,
                                          List<MarketSeriesPoint> stablecoinSeries,
                                          EvalParams params, int featureLookbackBars) {
        return assemblePoints(horizon, oneMin, benchmarkOneMin, fundingSeries, fearGreedSeries,
                etfFlowSeries, stablecoinSeries, params, featureLookbackBars, FEATURE_BAR_MILLIS);
    }

    static AssembledPoints assemblePoints(ForecastHorizon horizon, List<KlineBar> oneMin,
                                          List<KlineBar> benchmarkOneMin,
                                          List<MarketSeriesPoint> fundingSeries,
                                          List<MarketSeriesPoint> fearGreedSeries,
                                          List<MarketSeriesPoint> etfFlowSeries,
                                          List<MarketSeriesPoint> stablecoinSeries,
                                          EvalParams params, int featureLookbackBars,
                                          long decisionBarMillis) {
        validateDecisionBarMillis(decisionBarMillis);
        List<KlineBar> featureBars = KlineAggregator.aggregate(oneMin, decisionBarMillis);
        List<KlineBar> benchmarkFeatureBars = KlineAggregator.aggregate(benchmarkOneMin, decisionBarMillis);
        int horizonFeatureBars = horizonDecisionBars(horizon, decisionBarMillis);
        List<Integer> decisionFeatureIndexes = decisionFeatureIndexes(featureBars.size(), horizonFeatureBars);
        int points = decisionFeatureIndexes.size();
        List<KlineBar> decisionBars = decisionBars(featureBars, decisionFeatureIndexes);
        double[] closes = closes(featureBars);
        double[] volumes = volumes(featureBars);
        double[] assetReturns = logReturns(closes);
        double[] benchmarkReturns = benchmarkReturns(featureBars, benchmarkFeatureBars);
        double[] effectiveBenchmarkReturns = benchmarkReturns.length == 0 ? EMPTY_BENCHMARK_RETURNS : benchmarkReturns;
        double[] fundingByFeatureBar = fundingByFeatureBar(featureBars, fundingSeries);
        ContinuousFactorParams continuousFactorParams = ContinuousFactorParams.forBarMillis(decisionBarMillis);

        // ---- 装配每个决策点的 point-in-time 特征 + 三隔栏训练目标；后面按 window 只把 train 交给 fit ----
        List<ResearchFeatures> featuresByPoint = new ArrayList<>(points);
        List<BigDecimal> longBarrierReturnsByPoint = new ArrayList<>(points);
        List<HorizonPathOutcome> pathOutcomesByPoint = new ArrayList<>(points);
        List<TrainingSample> samplesByPoint = new ArrayList<>(points);
        for (int point = 0; point < points; point++) {
            int featureIndex = decisionFeatureIndexes.get(point);
            KlineBar decisionBar = featureBars.get(featureIndex);
            KlineBar exitBar = featureBars.get(featureIndex + horizonFeatureBars);
            long ti = decisionBar.closeTime();                             // 决策"当下"=该 5m bar 收盘时刻
            double funding = fundingByFeatureBar[featureIndex];
            int fng = SeriesAligner.asOf(fearGreedSeries, ti, NEUTRAL_FEAR_GREED).intValue();
            double etf = SeriesAligner.asOf(etfFlowSeries, ti, NEUTRAL_ONCHAIN).doubleValue();
            double stablecoin = SeriesAligner.asOf(stablecoinSeries, ti, NEUTRAL_ONCHAIN).doubleValue();
            ContinuousFactorVector continuousFactors = ContinuousFactorBuilder.build(
                    closes, volumes, fundingByFeatureBar, assetReturns, effectiveBenchmarkReturns,
                    featureIndex + 1, continuousFactorParams);
            int lookbackFrom = Math.max(0, featureIndex + 1 - featureLookbackBars); // 回看窗起点：晚期点恒 W 根，早期点不足则从 0（暖机语义不变）
            ResearchFeatures features = new ResearchFeatures(
                    featureBars.subList(lookbackFrom, featureIndex + 1),
                    funding, fng, etf, stablecoin, continuousFactors); // 绝不含未来
            BigDecimal entry = decisionBar.close();
            List<KlineBar> path = pathBars(oneMin, ti, horizon.millis());
            double sigma = HorizonScaledVolForecaster.scale(
                    VolatilityEstimator.ewmaVolatility(features.barsUpToNow(), params.lambda()), features, horizon);
            BarrierLabel label = sigma > 0
                    ? TripleBarrierLabeler.label(entry, params.k(), sigma, path)
                    : BarrierLabel.VERTICAL;
            BigDecimal fallbackClose = path.isEmpty() ? exitBar.close() : path.get(path.size() - 1).close();
            BigDecimal longReturn = longReturn(entry, fallbackClose, label, params.k(), sigma);
            HorizonPathOutcome pathOutcome = HorizonPathOutcome.from(entry, fallbackClose, path);
            double realizedForwardReturn = FactorMath.logReturn(exitBar.close().doubleValue(), entry.doubleValue());
            var realizedRegime = RegimeLabeler.label(path, sigma);
            featuresByPoint.add(features);
            longBarrierReturnsByPoint.add(longReturn);
            pathOutcomesByPoint.add(pathOutcome);
            samplesByPoint.add(new TrainingSample(features, longReturn, realizedForwardReturn, realizedRegime));
        }
        return new AssembledPoints(featureBars, decisionBars, List.copyOf(decisionFeatureIndexes),
                horizonFeatureBars, decisionBarMillis, points, featuresByPoint,
                longBarrierReturnsByPoint, pathOutcomesByPoint, samplesByPoint);
    }

    static int horizonDecisionBars(ForecastHorizon horizon, long decisionBarMillis) {
        long millis = horizon.millis();
        if (millis % decisionBarMillis != 0) {
            throw new IllegalArgumentException("horizon 必须能被决策K线整除: horizon=" + horizon
                    + " decisionBarMillis=" + decisionBarMillis);
        }
        return Math.toIntExact(millis / decisionBarMillis);
    }

    private static List<Integer> decisionFeatureIndexes(int featureBars, int horizonFeatureBars) {
        List<Integer> out = new ArrayList<>();
        // 每根 5m/15m bar 都是一个可预测点；horizon 只限制必须有足够未来路径可验证。
        for (int i = 0; i + horizonFeatureBars < featureBars; i++) {
            out.add(i);
        }
        return out;
    }

    private static List<KlineBar> decisionBars(List<KlineBar> featureBars,
                                               List<Integer> decisionIndexes) {
        if (decisionIndexes.isEmpty()) return List.of();
        List<KlineBar> out = new ArrayList<>(decisionIndexes.size());
        for (int index : decisionIndexes) {
            out.add(featureBars.get(index));
        }
        return out;
    }

    static long parseDecisionBarMillis(String raw) {
        String value = raw == null || raw.isBlank() ? "5m" : raw.trim().toLowerCase();
        long millis = switch (value) {
            case "5", "5m", "5min", "m5" -> FEATURE_BAR_MILLIS;
            case "15", "15m", "15min", "m15" -> FIFTEEN_MINUTE_BAR_MILLIS;
            default -> throw new IllegalArgumentException("decision interval 只支持 5m/15m: " + raw);
        };
        validateDecisionBarMillis(millis);
        return millis;
    }

    private static void validateDecisionBarMillis(long decisionBarMillis) {
        if (decisionBarMillis != FEATURE_BAR_MILLIS && decisionBarMillis != FIFTEEN_MINUTE_BAR_MILLIS) {
            throw new IllegalArgumentException("决策K线只支持 5m/15m: " + decisionBarMillis + "ms");
        }
    }

    static int minutes(long millis) {
        return Math.toIntExact(millis / 60_000L);
    }

    static int featureLookbackBars(long decisionBarMillis) {
        validateDecisionBarMillis(decisionBarMillis);
        return barsForDuration(FEATURE_LOOKBACK_MILLIS, decisionBarMillis);
    }

    static int barsForDuration(long durationMillis, long barMillis) {
        if (durationMillis <= 0L || barMillis <= 0L) {
            throw new IllegalArgumentException("durationMillis/barMillis 必须为正");
        }
        return Math.max(1, Math.toIntExact((durationMillis + barMillis - 1L) / barMillis));
    }

    private static double[] fundingByFeatureBar(List<KlineBar> featureBars, List<MarketSeriesPoint> fundingSeries) {
        double[] out = new double[featureBars.size()];
        for (int i = 0; i < featureBars.size(); i++) {
            out[i] = SeriesAligner.asOf(fundingSeries, featureBars.get(i).closeTime(), NEUTRAL_FUNDING).doubleValue();
        }
        return out;
    }

    private static double[] closes(List<KlineBar> bars) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) out[i] = value(bars.get(i).close());
        return out;
    }

    private static double[] volumes(List<KlineBar> bars) {
        double[] out = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) out[i] = value(bars.get(i).volume());
        return out;
    }

    private static double[] logReturns(double[] closes) {
        double[] out = new double[closes.length];
        for (int i = 1; i < closes.length; i++) out[i] = FactorMath.logReturn(closes[i], closes[i - 1]);
        return out;
    }

    private static double[] benchmarkReturns(List<KlineBar> assetHbars, List<KlineBar> benchmarkHbars) {
        if (assetHbars == null || assetHbars.isEmpty() || benchmarkHbars == null || benchmarkHbars.isEmpty()) {
            return EMPTY_BENCHMARK_RETURNS;
        }
        double[] out = new double[assetHbars.size()];
        int j = 0;
        Double previousBenchmarkClose = null;
        for (int i = 0; i < assetHbars.size(); i++) {
            long decisionTime = assetHbars.get(i).closeTime();
            while (j + 1 < benchmarkHbars.size() && benchmarkHbars.get(j + 1).closeTime() <= decisionTime) {
                j++;
            }
            if (benchmarkHbars.get(j).closeTime() > decisionTime) {
                continue;
            }
            double close = value(benchmarkHbars.get(j).close());
            if (previousBenchmarkClose != null) {
                out[i] = FactorMath.logReturn(close, previousBenchmarkClose);
            }
            previousBenchmarkClose = close;
        }
        return out;
    }

    private static double value(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    /**
     * 决策在 5m/15m bar 收盘后发生，三隔栏必须只看之后 1 个 horizon 内的底层路径。
     * 用二分定位首根 openTime > decisionCloseTime，避免长历史每个决策点都全表扫描。
     */
    static List<KlineBar> pathBars(List<KlineBar> oneMin, long decisionCloseTime, long horizonMillis) {
        if (oneMin == null || oneMin.isEmpty()) return List.of();
        long endTime = decisionCloseTime + horizonMillis;
        int lo = 0, hi = oneMin.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (oneMin.get(mid).openTime() <= decisionCloseTime) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        List<KlineBar> out = new ArrayList<>();
        for (int i = lo; i < oneMin.size(); i++) {
            KlineBar b = oneMin.get(i);
            if (b.openTime() > endTime) break;
            out.add(b);
        }
        return out;
    }

    /** 多头视角的单期收益：上栏/下栏按栏位价格成交；竖栏按 horizon 末尾 close 退出。 */
    private static BigDecimal longReturn(BigDecimal entry, BigDecimal fallbackClose,
                                         BarrierLabel label, double k, double sigma) {
        if (entry == null || entry.signum() == 0) return BigDecimal.ZERO;
        if (label == BarrierLabel.UPPER) {
            return BigDecimal.valueOf(k * sigma);
        }
        if (label == BarrierLabel.LOWER) {
            return BigDecimal.valueOf(-k * sigma);
        }
        return fallbackClose.subtract(entry).divide(entry, 10, RoundingMode.HALF_UP);
    }

    /** 复利净收益 = Π(1+r) − 1。 */
    static BigDecimal compound(List<BigDecimal> returns) {
        BigDecimal eq = BigDecimal.ONE;
        for (BigDecimal r : returns) {
            eq = eq.multiply(BigDecimal.ONE.add(r), COMPOUND_CONTEXT);
        }
        return eq.subtract(BigDecimal.ONE, COMPOUND_CONTEXT)
                .setScale(REPORT_RETURN_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private void writeReport(ComparisonReport report) {
        try {
            Path dir = ResearchEvalArtifacts.runDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(String.format("%s-%dh-%d.json",
                    report.symbol(), report.horizonHours(), System.currentTimeMillis()));
            Files.writeString(file, report.toJson());
            log.info("评估报告已写出: {} | {}", file, report.summary());
        } catch (IOException e) {
            log.warn("写评估报告失败（不影响返回）", e);
        }
    }
}
