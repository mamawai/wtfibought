package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.forecast.MarketRegime;
import com.mawai.wiibservice.agent.research.metrics.RegimeClassificationScore;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;
import com.mawai.wiibservice.agent.research.metrics.VolForecastScore;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 多输出样本外报告：三件一等输出各自对基准的诚实判决。
 * - 方向：复用方向尺子（buy&hold + naive 排列分位 + 风险调整指标）。
 * - 波动：QLIKE/MSE，model(EWMA) vs 多个基准——random-walk(上期|r|，弱/会塌零) + 上期EWMA + 滚动RV + 常数σ(climatology)。
 *   {@code volBeatsAllBaselines} 要求跑赢「最难的」那个基准（min QLIKE，通常是常数/滚动而非 random-walk），才算 vol 真有增量。
 * - regime：accuracy/balanced accuracy/macro F1 + persistence 朴素（封装在 {@link RegimeClassificationScore}）。
 */
public record MultiOutputReport(
        String symbol,
        int horizonHours,
        int totalHbars,
        int testPoints,
        // 方向腿
        RiskAdjustedMetrics directionMetrics,
        BigDecimal directionReturn,
        BigDecimal buyAndHoldReturn,
        boolean directionBeatsBuyHold,
        double directionNaivePercentile,
        boolean directionBeatsNaive,
        // 波动率腿：model vs 多基准（名→分数），verdict 以最难基准为准
        VolForecastScore volScore,
        Map<String, VolForecastScore> volBaselines,
        boolean volBeatsAllBaselines,
        // regime 腿
        RegimeClassificationScore regimeScore,
        Map<MarketRegime, Integer> actualRegimeCounts,
        Map<MarketRegime, Integer> predictedRegimeCounts
) {
    public String summary() {
        return String.format(
                "[%s %dh] 样本外 %d 点%n"
                        + "  方向: 收益=%.4f B&H=%.4f(%s) naive分位=%.1f%%(%s) Sharpe=%.2f%n"
                        + "  波动: QLIKE=%.4f MSE=%.6f | 基准QLIKE[%s] → %s%n"
                        + "  regime: 准确率=%.1f%% balAcc=%.1f%% macroF1=%.1f%% vs persistence=%.1f%%(%s)",
                symbol, horizonHours, testPoints,
                directionReturn.doubleValue(), buyAndHoldReturn.doubleValue(), directionBeatsBuyHold ? "跑赢" : "跑输",
                directionNaivePercentile * 100, directionBeatsNaive ? "显著" : "不显著", directionMetrics.annualizedSharpe(),
                volScore.qlike(), volScore.mse(), fmtVolBaselines(), volBeatsAllBaselines ? "跑赢全部基准" : "未跑赢全部基准",
                regimeScore.accuracy() * 100, regimeScore.balancedAccuracy() * 100, regimeScore.macroF1() * 100,
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

    /** regime 计数紧凑展示：R=震荡 U=趋势上 D=趋势下 S=冲击。 */
    private static String fmtDist(Map<MarketRegime, Integer> d) {
        return String.format("R=%d U=%d D=%d S=%d",
                d.getOrDefault(MarketRegime.RANGING, 0),
                d.getOrDefault(MarketRegime.TRENDING_UP, 0),
                d.getOrDefault(MarketRegime.TRENDING_DOWN, 0),
                d.getOrDefault(MarketRegime.SHOCK, 0));
    }
}
