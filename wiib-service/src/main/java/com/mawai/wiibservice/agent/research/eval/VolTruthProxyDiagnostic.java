package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.forecast.HarRvVolForecaster;
import com.mawai.wiibservice.agent.research.forecast.HorizonScaledVolForecaster;
import com.mawai.wiibservice.agent.research.forecast.QuantCoreForecaster;
import com.mawai.wiibservice.agent.research.forecast.VolForecaster;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.metrics.ForecastAccuracyComparison;
import com.mawai.wiibservice.agent.research.metrics.VolForecastScore;
import com.mawai.wiibservice.agent.research.stats.RealizedVarianceSeries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * vol 真值代理诊断：同一套 vol 预测(EWMA/HAR-RV)，在三种"已实现方差真值"下并排算 QLIKE + Diebold-Mariano。
 * - singleR2 ：现状真值=单根 (horizon 收益)²，1 个观测、最噪。
 * - pathRvClose：horizon 路径内 Σ 5m close-to-close r²，72/144/288 个观测。
 * - pathRvGk ：horizon 路径内 Σ Garman-Klass 单 bar 方差（用上 OHLC 高低价）。
 * 验"vol 弱 edge"是否只是真值太噪测不出：真值越准、DM 检验功效越高（Patton 2011 proxy-robust）。
 * 只读价格(barsUpToNow)，逐点无状态预测，天然 OOS 无泄漏。
 */
public final class VolTruthProxyDiagnostic {

    private VolTruthProxyDiagnostic() {
    }

    /**
     * 主入口：assemble(vol-only 价格)→walk-forward 取 OOS 点→收 HAR-RV/EWMA σ + 三真值→各真值下 QLIKE+DM。
     * 链下/benchmark 全传空：vol 预测只读价格(barsUpToNow)，不受影响，保持诊断轻量。
     */
    public static VolTruthProxyReport run(String symbol, ForecastHorizon horizon,
                                          List<KlineBar> baseBars, EvalParams params) {
        AssembledPoints a = ResearchEvalService.assemblePoints(horizon, baseBars, List.of(),
                List.of(), List.of(), List.of(), List.of(),
                params, ResearchEvalService.FEATURE_LOOKBACK_BARS, ResearchEvalService.FEATURE_BAR_MILLIS);
        int points = a.points();
        double[] baselineSigma = a.baselineSigmaByPoint();

        // 三真值的 horizon 路径方差（全 point 维度）：单根收益² / 路径ΣcloseR² / 路径ΣGK
        double[] pathClose = RealizedVarianceSeries.horizonSums(
                RealizedVarianceSeries.perBarCloseVariance(a.decisionIntervalBars()),
                a.decisionBarIndexes(), a.horizonDecisionBars());
        double[] pathGk = RealizedVarianceSeries.horizonSums(
                RealizedVarianceSeries.perBarGarmanKlass(a.decisionIntervalBars()),
                a.decisionBarIndexes(), a.horizonDecisionBars());
        double[] realizedFwd = new double[points];
        for (int i = 0; i < points; i++) {
            realizedFwd[i] = a.samplesByPoint().get(i).realizedForwardReturn();
        }
        double[] climSigma = expandingClimatologySigma(realizedFwd, a.decisionBarIndexes(), a.horizonDecisionBars());

        // HAR-RV horizon-scaled（无状态，逐点直接预测，天然 OOS）
        VolForecaster har = new HorizonScaledVolForecaster(
                HarRvVolForecaster.defaults(QuantCoreForecaster.DEFAULT_VOL_LAMBDA, a.decisionBarMillis()), horizon);

        // walk-forward OOS 点（与生产同切分）逐点收集
        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(
                points, params.testSize(), a.horizonDecisionBars(), params.embargoBars(), params.minTrain());
        List<Double> harList = new ArrayList<>();
        List<Double> ewmaList = new ArrayList<>();
        List<Double> climList = new ArrayList<>();
        List<Double> lag1List = new ArrayList<>();
        List<Double> realizedList = new ArrayList<>();
        List<Double> sqrtPathCloseList = new ArrayList<>();
        List<Double> sqrtPathGkList = new ArrayList<>();
        for (WalkForwardWindow w : wins) {
            for (int i = w.testStart(); i < w.testEnd(); i++) {
                harList.add(har.forecastSigma(a.featuresByPoint().get(i)));
                ewmaList.add(baselineSigma[i]);
                climList.add(climSigma[i]);
                lag1List.add(i >= 1 ? baselineSigma[i - 1] : 0.0);
                realizedList.add(realizedFwd[i]);
                sqrtPathCloseList.add(Math.sqrt(pathClose[i]));
                sqrtPathGkList.add(Math.sqrt(pathGk[i]));
            }
        }

        Map<String, double[]> sigma = new LinkedHashMap<>();
        sigma.put("har_rv", toArray(harList));
        sigma.put("ewma", toArray(ewmaList));
        sigma.put("climatology", toArray(climList));
        sigma.put("lag1_ewma", toArray(lag1List));

        int maxLag = Math.max(5, a.horizonDecisionBars()); // Newey-West 覆盖 horizon 重叠，与 MultiOutput 同口径
        List<TruthBlock> truths = List.of(
                truthBlock("singleR2", toArray(realizedList), sigma, maxLag),
                truthBlock("pathRvClose", toArray(sqrtPathCloseList), sigma, maxLag),
                truthBlock("pathRvGk", toArray(sqrtPathGkList), sigma, maxLag));
        return new VolTruthProxyReport(symbol, horizon.hours(), realizedList.size(), truths);
    }

    /** 扩张 climatology σ：只用已走完整个 horizon 的旧实现收益（无泄漏，与 MultiOutputEvalService 同口径）。 */
    private static double[] expandingClimatologySigma(double[] realizedFwd, List<Integer> decisionIdx, int horizonBars) {
        int points = realizedFwd.length;
        double[] out = new double[points];
        double sumSq = 0.0;
        int count = 0;
        int accumulatedUntil = 0;
        for (int i = 0; i < points; i++) {
            int knownEnd = MultiOutputEvalService.knownRealizedReturnEndExclusive(decisionIdx, i, horizonBars);
            while (accumulatedUntil < knownEnd) {
                double r = realizedFwd[accumulatedUntil++];
                sumSq += r * r;
                count++;
            }
            out[i] = count > 0 ? Math.sqrt(sumSq / count) : 0.0;
        }
        return out;
    }

    /** model→baseline 对比对（顺序即报告顺序）。 */
    private static final String[][] DM_PAIRS = {
            {"har_rv", "ewma"}, {"har_rv", "climatology"}, {"ewma", "climatology"},
            {"har_rv", "lag1_ewma"}, {"ewma", "lag1_ewma"}};

    /** 单一真值口径下：各预测器 QLIKE + 各 model/baseline 对的 DM。realizedArg=√真值方差（VolForecastScore 内部再平方）。 */
    private static TruthBlock truthBlock(String truth, double[] realizedArg,
                                         Map<String, double[]> sigma, int maxLag) {
        Map<String, Double> qlike = new LinkedHashMap<>();
        Map<String, double[]> losses = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : sigma.entrySet()) {
            qlike.put(e.getKey(), VolForecastScore.evaluate(e.getValue(), realizedArg).qlike());
            losses.put(e.getKey(), VolForecastScore.qlikeLosses(e.getValue(), realizedArg));
        }
        List<DmRow> dm = new ArrayList<>();
        for (String[] pair : DM_PAIRS) {
            ForecastAccuracyComparison c = ForecastAccuracyComparison.compareLosses(
                    losses.get(pair[0]), losses.get(pair[1]), maxLag);
            dm.add(new DmRow(pair[0], pair[1], c.meanLossDiff(), c.dmStatistic(), c.pValue(), c.modelBetterAt(0.05)));
        }
        return new TruthBlock(truth, qlike, dm);
    }

    private static double[] toArray(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    /** 单个 model vs baseline 的 DM 比较行。 */
    public record DmRow(String model, String baseline, double meanLossDiff,
                        double dmStatistic, double pValue, boolean modelBetter05) {
    }

    /** 一种真值口径下的全部评分。 */
    public record TruthBlock(String truth, Map<String, Double> qlikeByForecaster, List<DmRow> dm) {
    }

    /** 诊断报告：同 symbol/horizon 下三真值并排。 */
    public record VolTruthProxyReport(String symbol, int horizonHours, int oosPoints, List<TruthBlock> truths) {
    }
}
