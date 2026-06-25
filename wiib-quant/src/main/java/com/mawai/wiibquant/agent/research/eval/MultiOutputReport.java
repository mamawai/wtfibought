package com.mawai.wiibquant.agent.research.eval;

import com.mawai.wiibquant.agent.research.forecast.MarketRegime;
import com.mawai.wiibquant.agent.research.forecast.VolatilityRiskSummary;
import com.mawai.wiibquant.agent.research.metrics.ForecastAccuracyComparison;
import com.mawai.wiibquant.agent.research.metrics.RegimeClassificationScore;
import com.mawai.wiibquant.agent.research.metrics.ReturnSeries;
import com.mawai.wiibquant.agent.research.metrics.RiskAdjustedMetrics;
import com.mawai.wiibquant.agent.research.metrics.VolCalibrationReport;
import com.mawai.wiibquant.agent.research.metrics.VolForecastScore;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 多输出样本外报告：三件一等输出各自对基准的诚实判决。
 * - 方向：复用方向尺子（buy&hold + naive 排列分位 + 风险调整指标）。
 *   双口径：重叠口径（每决策 bar 出信号、horizon 内信号叠仓）复利虚高且排列分位偏乐观，仅作诊断；
 *   {@code directionNonOverlap*}（horizon 内不重复开仓）才是方向判决主口径——与 {@link StrategyLine} 同规则。
 * - 波动：QLIKE/MSE，model(EWMA) vs 多个基准——random-walk(上期|r|，弱/会塌零) + 上期EWMA + 滚动RV + 常数σ(climatology)。
 *   {@code volBeatsAllBaselines} 要求对「最难的」那个基准（min QLIKE，通常是常数/滚动而非 random-walk）的
 *   QLIKE 优势在 DM/Newey-West 检验下显著(α=0.05)，才算 vol 真有增量；裸点估计更低/打平不算赢。
 * - regime：accuracy/balanced accuracy/macro F1 + persistence 朴素（封装在 {@link RegimeClassificationScore}）。
 */
public record MultiOutputReport(
        String forecasterName,
        String symbol,
        int horizonHours,
        int decisionBarMinutes,
        int totalDecisionBars,
        int testPoints,
        // 方向腿——重叠口径（诊断参考，复利虚高）
        RiskAdjustedMetrics directionMetrics,
        BigDecimal directionReturn,
        BigDecimal buyAndHoldReturn,
        boolean directionBeatsBuyHold,
        double directionNaivePercentile,
        boolean directionBeatsNaive,
        // 方向腿——非重叠口径（horizon 内不重复开仓，判决主口径）
        BigDecimal directionNonOverlapReturn,
        double directionNonOverlapSharpe,
        int directionNonOverlapPeriods,
        boolean directionNonOverlapBeatsBuyHold,
        double directionNonOverlapNaivePercentile,
        boolean directionNonOverlapBeatsNaive,
        DirectionPathSummary directionPathSummary,
        // 方向腿逐期收益序列：DSR/PBO 的"现在就埋、计算后置"钩子，让 multi-*.json 也能进多重比较重判（与 StrategyLine 同口径）
        ReturnSeries directionReturnSeries,
        // 波动率腿：model vs 多基准（名→分数），verdict 以最难基准为准
        VolForecastScore volScore,
        Map<String, VolForecastScore> volBaselines,
        String bestVolBaselineName,
        ForecastAccuracyComparison volQlikeVsBestBaseline,
        VolCalibrationReport volCalibration,
        VolatilityRiskSummary volRiskSummary,
        boolean volBeatsAllBaselines,
        // regime 腿
        RegimeClassificationScore regimeScore,
        Map<MarketRegime, Integer> actualRegimeCounts,
        Map<MarketRegime, Integer> predictedRegimeCounts
) {
    public String summary() {
        return String.format(
                "[%s %s %dm→%dh] 样本外 %d 点%n"
                        + "  方向(非重叠主口径): 收益=%.4f B&H=%.4f(%s) naive分位=%.1f%%(%s) Sharpe=%.2f n=%d | 重叠诊断: 收益=%.4f naive分位=%.1f%% Sharpe=%.2f%n"
                        + "  路径: traded=%d avgDir=%.1fbps avgMFE=%.1fbps avgMAE=%.1fbps%n"
                        + "  波动: QLIKE=%.4f MSE=%.6f | 基准QLIKE[%s] | best=%s p=%.3f(%s) | 校准ratio=%.2f buckets[%s] | ctx median=%dbps p90=%dbps elevated+=%.1f%% stressed=%.1f%% → %s%n"
                        + "  regime: balAcc=%.1f%%(chance=%.1f%% %s) macroF1=%.1f%% | 次:准确率=%.1f%% vs persistence=%.1f%%(%s)",
                symbol, forecasterName, decisionBarMinutes, horizonHours, testPoints,
                directionNonOverlapReturn.doubleValue(), buyAndHoldReturn.doubleValue(),
                directionNonOverlapBeatsBuyHold ? "跑赢" : "跑输",
                directionNonOverlapNaivePercentile * 100, directionNonOverlapBeatsNaive ? "显著" : "不显著",
                directionNonOverlapSharpe, directionNonOverlapPeriods,
                directionReturn.doubleValue(), directionNaivePercentile * 100, directionMetrics.annualizedSharpe(),
                directionPathSummary.tradedPoints(), directionPathSummary.avgDirectionalChangeBps(),
                directionPathSummary.avgMaxFavorableBps(), directionPathSummary.avgMaxAdverseBps(),
                volScore.qlike(), volScore.mse(), fmtVolBaselines(),
                bestVolBaselineName, volQlikeVsBestBaseline.pValue(),
                volQlikeVsBestBaseline.modelBetterAt(0.05) ? "显著" : "不显著",
                volCalibration.realizedToPredictedRatio(), fmtCalibrationBuckets(),
                volRiskSummary.medianExpectedMoveBps(), volRiskSummary.p90ExpectedMoveBps(),
                volRiskSummary.elevatedOrStressedShare() * 100.0, volRiskSummary.stressedShare() * 100.0,
                volBeatsAllBaselines ? "跑赢全部基准" : "未跑赢全部基准",
                regimeScore.balancedAccuracy() * 100, regimeScore.balancedChanceLevel() * 100,
                regimeScore.beatsBalancedChance() ? "有技能" : "无技能",
                regimeScore.macroF1() * 100,
                regimeScore.accuracy() * 100,
                regimeScore.naiveAccuracy() * 100, regimeScore.beatsNaive() ? "跑赢" : "跑平/输")
                + String.format("%n  regime分布 actual[%s] pred[%s]",
                fmtDist(actualRegimeCounts), fmtDist(predictedRegimeCounts));
    }

    /** vol 各基准 QLIKE 紧凑展示（保留插入顺序：random-walk→上期EWMA→滚动→常数）。 */
    private String fmtVolBaselines() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, VolForecastScore> e : volBaselines.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(String.format("%.2f", e.getValue().qlike()));
        }
        return sb.toString();
    }

    /** 校准分桶 ratio：>1 低估波动，<1 高估波动。 */
    private String fmtCalibrationBuckets() {
        StringBuilder sb = new StringBuilder();
        for (VolCalibrationReport.Bucket b : volCalibration.buckets()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(b.bucketIndex()).append('=').append(String.format("%.2f", b.realizedToPredictedRatio()));
        }
        return sb.toString();
    }

    /** regime 计数紧凑展示：R=震荡 U=趋势上 D=趋势下 S=冲击。 */
    private static String fmtDist(Map<MarketRegime, Integer> d) {
        return String.format("R=%d U=%d D=%d S=%d",
                d.getOrDefault(MarketRegime.RANGING, 0),
                d.getOrDefault(MarketRegime.TRENDING_UP, 0),
                d.getOrDefault(MarketRegime.TRENDING_DOWN, 0),
                d.getOrDefault(MarketRegime.SHOCK, 0));
    }
}
