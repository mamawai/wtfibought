package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.benchmark.BenchmarkCalculator;
import com.mawai.wiibservice.agent.research.forecast.MarketRegime;
import com.mawai.wiibservice.agent.research.forecast.MultiOutputForecast;
import com.mawai.wiibservice.agent.research.forecast.MultiOutputForecaster;
import com.mawai.wiibservice.agent.research.forecast.HorizonScaledVolForecaster;
import com.mawai.wiibservice.agent.research.forecast.VolatilityRiskContext;
import com.mawai.wiibservice.agent.research.forecast.VolatilityRiskSummary;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.label.RegimeLabeler;
import com.mawai.wiibservice.agent.research.metrics.RegimeClassificationScore;
import com.mawai.wiibservice.agent.research.metrics.ReturnSeries;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;
import com.mawai.wiibservice.agent.research.metrics.VolForecastScore;
import com.mawai.wiibservice.agent.research.metrics.ForecastAccuracyComparison;
import com.mawai.wiibservice.agent.research.metrics.VolCalibrationReport;
import com.mawai.wiibservice.agent.research.series.MarketSeriesPoint;
import com.mawai.wiibservice.agent.research.stats.VolatilityEstimator;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多输出评估编排：复用 {@link ResearchEvalService#assemblePoints}（同一份无泄漏 point-in-time 装配），
 * walk-forward 逐 test 点收集 vol/regime/direction 三输出，对各自基准出诚实判决。
 * - 方向：复用方向尺子（buy&amp;hold + naive 排列分位 + 风险调整指标）。
 * - 波动：model(预测 σ) vs 朴素基准（上一条同 horizon 真实收益、滚动/常数 σ），QLIKE 低者胜。
 * - regime：预测 vs 事后标签（RegimeLabeler），分类准确率 vs persistence（封装在 score）。
 */
public final class MultiOutputEvalService {

    /** rolling RV 基准看最近 3 天信号，不随 5m/15m bar 粒度漂移。 */
    private static final long ROLLING_VOL_LOOKBACK_MILLIS = Duration.ofDays(3).toMillis();
    /** QLIKE loss-diff 的 Newey-West 最小滞后；实际会至少覆盖一个 horizon 的重叠长度。 */
    private static final int VOL_LOSS_DIFF_MIN_LAG = 5;
    private static final int VOL_CALIBRATION_BUCKETS = 5;
    private static final int PROGRESS_BAR_WIDTH = 24;
    private static final int PROGRESS_TARGET_UPDATES = 20;

    private MultiOutputEvalService() {
    }

    public static MultiOutputReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                                 List<KlineBar> benchmarkOneMin,
                                                 List<MarketSeriesPoint> fundingSeries,
                                                 List<MarketSeriesPoint> fearGreedSeries,
                                                 List<MarketSeriesPoint> etfFlowSeries,
                                                 List<MarketSeriesPoint> stablecoinSeries,
                                                 MultiOutputForecaster forecaster, EvalParams params) {
        return evaluateBars(symbol, horizon, oneMin, benchmarkOneMin, fundingSeries, fearGreedSeries,
                etfFlowSeries, stablecoinSeries, forecaster, params, ResearchEvalService.FEATURE_LOOKBACK_BARS);
    }

    static MultiOutputReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                          List<KlineBar> benchmarkOneMin,
                                          List<MarketSeriesPoint> fundingSeries,
                                          List<MarketSeriesPoint> fearGreedSeries,
                                          List<MarketSeriesPoint> etfFlowSeries,
                                          List<MarketSeriesPoint> stablecoinSeries,
                                          MultiOutputForecaster forecaster, EvalParams params, int featureLookbackBars) {
        return evaluateBars(symbol, horizon, oneMin, benchmarkOneMin, fundingSeries, fearGreedSeries,
                etfFlowSeries, stablecoinSeries, forecaster, params, featureLookbackBars,
                ResearchEvalService.FEATURE_BAR_MILLIS);
    }

    static MultiOutputReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                          List<KlineBar> benchmarkOneMin,
                                          List<MarketSeriesPoint> fundingSeries,
                                          List<MarketSeriesPoint> fearGreedSeries,
                                          List<MarketSeriesPoint> etfFlowSeries,
                                          List<MarketSeriesPoint> stablecoinSeries,
                                          MultiOutputForecaster forecaster, EvalParams params,
                                          int featureLookbackBars, long decisionBarMillis) {
        return evaluateBars(symbol, horizon, oneMin, benchmarkOneMin, fundingSeries, fearGreedSeries,
                etfFlowSeries, stablecoinSeries, forecaster, params, featureLookbackBars, decisionBarMillis,
                EvalProgress.NOOP);
    }

    static MultiOutputReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                          List<KlineBar> benchmarkOneMin,
                                          List<MarketSeriesPoint> fundingSeries,
                                          List<MarketSeriesPoint> fearGreedSeries,
                                          List<MarketSeriesPoint> etfFlowSeries,
                                          List<MarketSeriesPoint> stablecoinSeries,
                                          MultiOutputForecaster forecaster, EvalParams params,
                                          int featureLookbackBars, long decisionBarMillis,
                                          EvalProgress progress) {
        EvalProgress evalProgress = progress == null ? EvalProgress.NOOP : progress;
        evalProgress.update("assemble", 0, 1);
        AssembledPoints a = ResearchEvalService.assemblePoints(horizon, oneMin, benchmarkOneMin,
                fundingSeries, fearGreedSeries, etfFlowSeries, stablecoinSeries,
                params, featureLookbackBars, decisionBarMillis);
        evalProgress.update("assemble", 1, 1);
        List<KlineBar> decisionBars = a.decisionBars();
        int points = a.points();
        double lambda = params.lambda();
        int rollingVolWindow = ResearchEvalService.barsForDuration(ROLLING_VOL_LOOKBACK_MILLIS, a.decisionBarMillis());
        int volLossDiffMaxLag = Math.max(VOL_LOSS_DIFF_MIN_LAG, a.horizonDecisionBars());

        // ---- 每个决策点的 actual（label，用未来——它是 ground truth，无泄漏问题）+ vol 各基准（只用截至 i 的过去，无泄漏）----
        double[] realizedReturnByPoint = new double[points];   // vol 实现：未来 horizon 对数收益
        double[] rwSigmaByPoint = new double[points];          // 基准1 random-walk：上一条同 horizon |对数收益|（弱、会塌零）
        double[] prevEwmaSigmaByPoint = new double[points];    // 基准2 上期 EWMA σ：model 的 lag-1（测"最新一根是否加分"）
        double[] rollingSigmaByPoint = new double[points];     // 基准3 滚动 RV：最近 3 天同 horizon 收益等权 RMS
        double[] constSigmaByPoint = new double[points];       // 基准4 常数 σ：扩张 climatology（截至 i 全部历史）
        MarketRegime[] actualRegimeByPoint = new MarketRegime[points];
        MarketRegime[] knownRegimeByPoint = new MarketRegime[points];
        double constSumSq = 0.0;
        int constCount = 0;
        int constAccumulatedUntil = 0;
        for (int i = 0; i < points; i++) {
            realizedReturnByPoint[i] = a.samplesByPoint().get(i).realizedForwardReturn();
        }
        double[] realizedReturnSqPrefix = squaredPrefix(realizedReturnByPoint);
        evalProgress.update("vol-baselines", 0, points);
        for (int i = 0; i < points; i++) {
            int knownReturnEnd = knownRealizedReturnEndExclusive(
                    a.decisionBarIndexes(), i, a.horizonDecisionBars());
            if (knownReturnEnd > 0) {
                rwSigmaByPoint[i] = Math.abs(realizedReturnByPoint[knownReturnEnd - 1]);
            }
            while (constAccumulatedUntil < knownReturnEnd) {
                double ret = realizedReturnByPoint[constAccumulatedUntil++];
                constSumSq += ret * ret;
                constCount++;
            }
            constSigmaByPoint[i] = constCount > 0 ? Math.sqrt(constSumSq / constCount) : 0.0;
            double baselineSigma = horizonEwmaSigma(a.featuresByPoint().get(i), horizon, lambda);
            prevEwmaSigmaByPoint[i] = i >= 1 ? horizonEwmaSigma(a.featuresByPoint().get(i - 1), horizon, lambda) : 0.0;
            rollingSigmaByPoint[i] = rollingSigma(realizedReturnSqPrefix, knownReturnEnd, rollingVolWindow);
            MarketRegime sampleRegime = a.samplesByPoint().get(i).realizedRegime();
            if (sampleRegime != null) {
                actualRegimeByPoint[i] = sampleRegime;
            } else {
                List<KlineBar> futurePath = ResearchEvalService.pathBars(oneMin, decisionBars.get(i).closeTime(), horizon.millis());
                actualRegimeByPoint[i] = RegimeLabeler.label(futurePath, baselineSigma);
            }
            // regime 的 naive 基准只能看已走完整个 horizon 的旧标签；相邻 actual[i-1] 对 5m→H 是未来信息。
            knownRegimeByPoint[i] = knownReturnEnd > 0 ? actualRegimeByPoint[knownReturnEnd - 1] : null;
            progressEvery(evalProgress, "vol-baselines", i + 1, points);
        }

        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(
                points, params.testSize(), a.horizonDecisionBars(), params.embargoBars(), params.minTrain());

        // ---- 逐窗 fit（转发方向腿）→ 逐 test 点收集三输出 ----
        List<Integer> positions = new ArrayList<>();
        List<BigDecimal> testLongReturns = new ArrayList<>();
        List<BigDecimal> posReturns = new ArrayList<>();
        List<HorizonPathOutcome> pathOutcomes = new ArrayList<>();
        List<Double> predSigma = new ArrayList<>();
        List<Double> realizedForVol = new ArrayList<>();
        List<Double> rwSigmaForVol = new ArrayList<>();
        List<Double> prevEwmaForVol = new ArrayList<>();
        List<Double> rollingForVol = new ArrayList<>();
        List<Double> constForVol = new ArrayList<>();
        List<VolatilityRiskContext> volContexts = new ArrayList<>();
        List<MarketRegime> predRegime = new ArrayList<>();
        List<MarketRegime> actualRegime = new ArrayList<>();
        List<MarketRegime> naiveRegime = new ArrayList<>();
        int firstDecisionBarIndex = -1, lastExitBarIndex = -1;
        int totalWalkForwardPoints = wins.stream().mapToInt(WalkForwardWindow::testSize).sum();
        int walkedPoints = 0;
        evalProgress.update("walk-forward", 0, totalWalkForwardPoints);
        for (WalkForwardWindow w : wins) {
            if (firstDecisionBarIndex < 0) {
                firstDecisionBarIndex = a.decisionBarIndexes().get(w.testStart());
            }
            int lastTestPoint = w.testEnd() - 1;
            lastExitBarIndex = Math.max(lastExitBarIndex,
                    a.decisionBarIndexes().get(lastTestPoint) + a.horizonDecisionBars());
            MultiOutputForecaster trained = forecaster.fit(
                    List.copyOf(a.samplesByPoint().subList(w.trainStart(), w.trainEnd())));
            for (int i = w.testStart(); i < w.testEnd(); i++) {
                MultiOutputForecast f = trained.forecast(a.featuresByPoint().get(i));
                int dir = f.direction().direction();
                BigDecimal longReturn = a.longBarrierReturnsByPoint().get(i);
                positions.add(dir);
                testLongReturns.add(longReturn);
                posReturns.add(longReturn.multiply(BigDecimal.valueOf(dir)));
                pathOutcomes.add(a.pathOutcomesByPoint().get(i));
                predSigma.add(f.expectedVolatility());
                volContexts.add(f.volatilityContext());
                realizedForVol.add(realizedReturnByPoint[i]);
                rwSigmaForVol.add(rwSigmaByPoint[i]);
                prevEwmaForVol.add(prevEwmaSigmaByPoint[i]);
                rollingForVol.add(rollingSigmaByPoint[i]);
                constForVol.add(constSigmaByPoint[i]);
                predRegime.add(f.regime());
                actualRegime.add(actualRegimeByPoint[i]);
                naiveRegime.add(knownRegimeByPoint[i]);
                progressEvery(evalProgress, "walk-forward", ++walkedPoints, totalWalkForwardPoints);
            }
        }

        // ---- 方向腿判决（复用方向尺子）----
        evalProgress.update("direction-score", 0, 1);
        ReturnSeries series = new ReturnSeries(forecaster.name(), posReturns, horizon.periodsPerYear());
        RiskAdjustedMetrics dirMetrics = RiskAdjustedMetrics.from(series);
        BigDecimal buyHold = (firstDecisionBarIndex >= 0 && lastExitBarIndex < a.decisionIntervalBars().size())
                ? BenchmarkCalculator.buyAndHoldReturn(
                a.decisionIntervalBars().subList(firstDecisionBarIndex, lastExitBarIndex + 1))
                : BigDecimal.ZERO;
        BigDecimal dirReturn = ResearchEvalService.compound(posReturns);
        evalProgress.update("permutation", 0, params.iterations());
        double naivePct = positions.isEmpty() ? 0.0
                : BenchmarkCalculator.permutationPercentile(positions, testLongReturns, params.iterations(), params.seed(),
                done -> progressEvery(evalProgress, "permutation", done, params.iterations()));
        DirectionPathSummary directionPathSummary = DirectionPathSummary.from(positions, pathOutcomes);
        evalProgress.update("direction-score", 1, 1);

        // ---- 波动率判决（model 预测 σ vs 多基准；verdict 以最难基准[min QLIKE]为准，random-walk 只作对照）----
        evalProgress.update("vol-score", 0, 1);
        double[] realizedArr = toArray(realizedForVol);
        double[] predSigmaArr = toArray(predSigma);
        VolForecastScore volScore = VolForecastScore.evaluate(predSigmaArr, realizedArr);
        Map<String, double[]> volBaselineSigma = new LinkedHashMap<>();
        volBaselineSigma.put("randomWalk", toArray(rwSigmaForVol));
        volBaselineSigma.put("prevEwma", toArray(prevEwmaForVol));
        volBaselineSigma.put("rolling3d", toArray(rollingForVol));
        volBaselineSigma.put("constant", toArray(constForVol));
        Map<String, VolForecastScore> volBaselines = new LinkedHashMap<>();
        String bestBaselineName = "";
        double[] bestBaselineSigma = new double[0];
        double bestBaselineQlike = Double.POSITIVE_INFINITY;
        for (Map.Entry<String, double[]> e : volBaselineSigma.entrySet()) {
            VolForecastScore score = VolForecastScore.evaluate(e.getValue(), realizedArr);
            volBaselines.put(e.getKey(), score);
            if (score.qlike() < bestBaselineQlike) {
                bestBaselineQlike = score.qlike();
                bestBaselineName = e.getKey();
                bestBaselineSigma = e.getValue();
            }
        }
        boolean volBeatsAllBaselines = volScore.qlike() < bestBaselineQlike;
        ForecastAccuracyComparison volQlikeVsBestBaseline = ForecastAccuracyComparison.compareLosses(
                VolForecastScore.qlikeLosses(predSigmaArr, realizedArr),
                VolForecastScore.qlikeLosses(bestBaselineSigma, realizedArr),
                volLossDiffMaxLag);
        VolCalibrationReport volCalibration = VolCalibrationReport.evaluate(
                predSigmaArr, realizedArr, VOL_CALIBRATION_BUCKETS);
        VolatilityRiskSummary volRiskSummary = VolatilityRiskSummary.from(volContexts);
        evalProgress.update("vol-score", 1, 1);

        // ---- regime 判决（model vs persistence，封装在 score）----
        evalProgress.update("regime-score", 0, 1);
        RegimeClassificationScore regimeScore = RegimeClassificationScore.evaluate(
                predRegime.toArray(new MarketRegime[0]),
                actualRegime.toArray(new MarketRegime[0]),
                naiveRegime.toArray(new MarketRegime[0]));
        evalProgress.update("regime-score", 1, 1);

        return new MultiOutputReport(forecaster.name(), symbol, horizon.hours(),
                ResearchEvalService.minutes(a.decisionBarMillis()), points, positions.size(),
                dirMetrics, dirReturn, buyHold, dirReturn.compareTo(buyHold) > 0,
                naivePct, naivePct >= params.naivePercentileThreshold(), directionPathSummary,
                volScore, volBaselines, bestBaselineName, volQlikeVsBestBaseline, volCalibration, volRiskSummary,
                volBeatsAllBaselines,
                regimeScore,
                countByRegime(actualRegime), countByRegime(predRegime));
    }

    /** 各 regime 类别计数（诊断标签/预测分布是否塌缩）。 */
    private static Map<MarketRegime, Integer> countByRegime(List<MarketRegime> regimes) {
        Map<MarketRegime, Integer> counts = new EnumMap<>(MarketRegime.class);
        for (MarketRegime r : regimes) counts.merge(r, 1, Integer::sum);
        return counts;
    }

    private static double horizonEwmaSigma(com.mawai.wiibservice.agent.research.forecast.ResearchFeatures features,
                                           ForecastHorizon horizon,
                                           double lambda) {
        return HorizonScaledVolForecaster.scale(
                VolatilityEstimator.ewmaVolatility(features.barsUpToNow(), lambda), features, horizon);
    }

    private static double[] squaredPrefix(double[] values) {
        double[] out = new double[values.length + 1];
        for (int i = 0; i < values.length; i++) {
            double v = values[i];
            out[i + 1] = out[i] + v * v;
        }
        return out;
    }

    private static double rollingSigma(double[] squaredPrefix, int endExclusive, int window) {
        int safeEnd = Math.max(0, Math.min(endExclusive, squaredPrefix.length - 1));
        int start = Math.max(0, safeEnd - window);
        int n = safeEnd - start;
        if (n <= 0) return 0.0;
        double sumSq = squaredPrefix[safeEnd] - squaredPrefix[start];
        return Math.sqrt(sumSq / n);
    }

    private static void progressEvery(EvalProgress progress, String stage, int done, int total) {
        if (progress == null || total <= 0) {
            return;
        }
        int step = Math.max(1, total / PROGRESS_TARGET_UPDATES);
        if (done == 0 || done >= total || done % step == 0) {
            progress.update(stage, done, total);
        }
    }

    static String progressBar(int done, int total) {
        if (total <= 0) {
            return ".".repeat(PROGRESS_BAR_WIDTH);
        }
        int filled = Math.max(0, Math.min(PROGRESS_BAR_WIDTH,
                (int) Math.round((double) done * PROGRESS_BAR_WIDTH / total)));
        return "#".repeat(filled) + ".".repeat(PROGRESS_BAR_WIDTH - filled);
    }

    /** 当前点只能使用已经走完整个 horizon 的历史标签，避免 5m→6h 这类重叠标签泄漏。 */
    static int knownRealizedReturnEndExclusive(List<Integer> decisionBarIndexes, int point, int horizonDecisionBars) {
        if (decisionBarIndexes == null || point <= 0 || horizonDecisionBars <= 0) {
            return 0;
        }
        int currentBarIndex = decisionBarIndexes.get(point);
        int latestKnownDecisionBar = currentBarIndex - horizonDecisionBars;
        int lo = 0, hi = point;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (decisionBarIndexes.get(mid) <= latestKnownDecisionBar) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static double[] toArray(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }
}
