package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.benchmark.BenchmarkCalculator;
import com.mawai.wiibservice.agent.research.forecast.MarketRegime;
import com.mawai.wiibservice.agent.research.forecast.MultiOutputForecast;
import com.mawai.wiibservice.agent.research.forecast.MultiOutputForecaster;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.label.RegimeLabeler;
import com.mawai.wiibservice.agent.research.metrics.RegimeClassificationScore;
import com.mawai.wiibservice.agent.research.metrics.ReturnSeries;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;
import com.mawai.wiibservice.agent.research.metrics.VolForecastScore;
import com.mawai.wiibservice.agent.research.series.MarketSeriesPoint;
import com.mawai.wiibservice.agent.research.stats.VolatilityEstimator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多输出评估编排：复用 {@link ResearchEvalService#assemblePoints}（同一份无泄漏 point-in-time 装配），
 * walk-forward 逐 test 点收集 vol/regime/direction 三输出，对各自基准出诚实判决。
 * - 方向：复用方向尺子（buy&amp;hold + naive 排列分位 + 风险调整指标）。
 * - 波动：model(预测 σ) vs 朴素(random-walk=上一个 H-bar |对数收益|)，QLIKE 低者胜。
 * - regime：预测 vs 事后标签（RegimeLabeler），分类准确率 vs persistence（封装在 score）。
 */
public final class MultiOutputEvalService {

    /** 滚动 RV 基准窗口（per-H-bar）：等权最近 N 根；诊断旋钮，非寻优参数。 */
    private static final int ROLLING_VOL_WINDOW = 30;

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
        AssembledPoints a = ResearchEvalService.assemblePoints(horizon, oneMin, benchmarkOneMin,
                fundingSeries, fearGreedSeries, etfFlowSeries, stablecoinSeries, params, featureLookbackBars);
        List<KlineBar> hbars = a.hbars();
        int points = a.points();
        double lambda = params.lambda();

        // ---- 每个决策点的 actual（label，用未来——它是 ground truth，无泄漏问题）+ vol 各基准（只用截至 i 的过去，无泄漏）----
        double[] realizedReturnByPoint = new double[points];   // vol 实现：下一个 H-bar 对数收益
        double[] rwSigmaByPoint = new double[points];          // 基准1 random-walk：上一个 H-bar |对数收益|（弱、会塌零）
        double[] prevEwmaSigmaByPoint = new double[points];    // 基准2 上期 EWMA σ：model 的 lag-1（测"最新一根是否加分"）
        double[] rollingSigmaByPoint = new double[points];     // 基准3 滚动 RV：最近 ROLLING_VOL_WINDOW 根等权 RMS
        double[] constSigmaByPoint = new double[points];       // 基准4 常数 σ：扩张 climatology（截至 i 全部历史）
        MarketRegime[] actualRegimeByPoint = new MarketRegime[points];
        double constSumSq = 0.0;
        int constCount = 0;
        for (int i = 0; i < points; i++) {
            double close = hbars.get(i).close().doubleValue();
            double nextClose = hbars.get(i + 1).close().doubleValue();
            realizedReturnByPoint[i] = Math.log(nextClose / close);
            if (i >= 1) {
                double prevRet = Math.log(close / hbars.get(i - 1).close().doubleValue());
                rwSigmaByPoint[i] = Math.abs(prevRet);
                constSumSq += prevRet * prevRet;   // 扩张累积 ret[1..i]
                constCount++;
            }
            constSigmaByPoint[i] = constCount > 0 ? Math.sqrt(constSumSq / constCount) : 0.0;
            int lookbackFrom = Math.max(0, i + 1 - featureLookbackBars);
            double baselineSigma = VolatilityEstimator.ewmaVolatility(hbars.subList(lookbackFrom, i + 1), lambda);
            prevEwmaSigmaByPoint[i] = VolatilityEstimator.ewmaVolatility(hbars.subList(lookbackFrom, i), lambda); // 截至 i-1
            rollingSigmaByPoint[i] = VolatilityEstimator.rollingSigma(hbars.subList(0, i + 1), ROLLING_VOL_WINDOW);
            List<KlineBar> futurePath = ResearchEvalService.pathBars(oneMin, hbars.get(i).closeTime(), horizon.millis());
            actualRegimeByPoint[i] = RegimeLabeler.label(futurePath, baselineSigma);
        }

        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(
                points, params.testSize(), ResearchEvalService.LABEL_PURGE_HBARS, params.embargoBars(), params.minTrain());

        // ---- 逐窗 fit（转发方向腿）→ 逐 test 点收集三输出 ----
        List<Integer> positions = new ArrayList<>();
        List<BigDecimal> testLongReturns = new ArrayList<>();
        List<BigDecimal> posReturns = new ArrayList<>();
        List<Double> predSigma = new ArrayList<>();
        List<Double> realizedForVol = new ArrayList<>();
        List<Double> rwSigmaForVol = new ArrayList<>();
        List<Double> prevEwmaForVol = new ArrayList<>();
        List<Double> rollingForVol = new ArrayList<>();
        List<Double> constForVol = new ArrayList<>();
        List<MarketRegime> predRegime = new ArrayList<>();
        List<MarketRegime> actualRegime = new ArrayList<>();
        int firstTest = -1, lastTestExclusive = -1;
        for (WalkForwardWindow w : wins) {
            if (firstTest < 0) firstTest = w.testStart();
            lastTestExclusive = w.testEnd() + 1;
            MultiOutputForecaster trained = forecaster.fit(
                    List.copyOf(a.samplesByPoint().subList(w.trainStart(), w.trainEnd())));
            for (int i = w.testStart(); i < w.testEnd(); i++) {
                MultiOutputForecast f = trained.forecast(a.featuresByPoint().get(i));
                int dir = f.direction().direction();
                BigDecimal longReturn = a.longBarrierReturnsByPoint().get(i);
                positions.add(dir);
                testLongReturns.add(longReturn);
                posReturns.add(longReturn.multiply(BigDecimal.valueOf(dir)));
                predSigma.add(f.expectedVolatility());
                realizedForVol.add(realizedReturnByPoint[i]);
                rwSigmaForVol.add(rwSigmaByPoint[i]);
                prevEwmaForVol.add(prevEwmaSigmaByPoint[i]);
                rollingForVol.add(rollingSigmaByPoint[i]);
                constForVol.add(constSigmaByPoint[i]);
                predRegime.add(f.regime());
                actualRegime.add(actualRegimeByPoint[i]);
            }
        }

        // ---- 方向腿判决（复用方向尺子）----
        ReturnSeries series = new ReturnSeries(forecaster.name(), posReturns, horizon.periodsPerYear());
        RiskAdjustedMetrics dirMetrics = RiskAdjustedMetrics.from(series);
        BigDecimal buyHold = (firstTest >= 0 && lastTestExclusive <= hbars.size())
                ? BenchmarkCalculator.buyAndHoldReturn(hbars.subList(firstTest, lastTestExclusive))
                : BigDecimal.ZERO;
        BigDecimal dirReturn = ResearchEvalService.compound(posReturns);
        double naivePct = positions.isEmpty() ? 0.0
                : BenchmarkCalculator.permutationPercentile(positions, testLongReturns, params.iterations(), params.seed());

        // ---- 波动率判决（model 预测 σ vs 多基准；verdict 以最难基准[min QLIKE]为准，random-walk 只作对照）----
        double[] realizedArr = toArray(realizedForVol);
        VolForecastScore volScore = VolForecastScore.evaluate(toArray(predSigma), realizedArr);
        Map<String, VolForecastScore> volBaselines = new LinkedHashMap<>();
        volBaselines.put("randomWalk", VolForecastScore.evaluate(toArray(rwSigmaForVol), realizedArr));
        volBaselines.put("prevEwma", VolForecastScore.evaluate(toArray(prevEwmaForVol), realizedArr));
        volBaselines.put("rolling" + ROLLING_VOL_WINDOW, VolForecastScore.evaluate(toArray(rollingForVol), realizedArr));
        volBaselines.put("constant", VolForecastScore.evaluate(toArray(constForVol), realizedArr));
        double bestBaselineQlike = volBaselines.values().stream()
                .mapToDouble(VolForecastScore::qlike).min().orElse(Double.POSITIVE_INFINITY);
        boolean volBeatsAllBaselines = volScore.qlike() < bestBaselineQlike;

        // ---- regime 判决（model vs persistence，封装在 score）----
        RegimeClassificationScore regimeScore = RegimeClassificationScore.evaluate(
                predRegime.toArray(new MarketRegime[0]), actualRegime.toArray(new MarketRegime[0]));

        return new MultiOutputReport(symbol, horizon.hours(), hbars.size(), positions.size(),
                dirMetrics, dirReturn, buyHold, dirReturn.compareTo(buyHold) > 0,
                naivePct, naivePct >= params.naivePercentileThreshold(),
                volScore, volBaselines, volBeatsAllBaselines,
                regimeScore,
                countByRegime(actualRegime), countByRegime(predRegime));
    }

    /** 各 regime 类别计数（诊断标签/预测分布是否塌缩）。 */
    private static Map<MarketRegime, Integer> countByRegime(List<MarketRegime> regimes) {
        Map<MarketRegime, Integer> counts = new EnumMap<>(MarketRegime.class);
        for (MarketRegime r : regimes) counts.merge(r, 1, Integer::sum);
        return counts;
    }

    private static double[] toArray(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }
}
