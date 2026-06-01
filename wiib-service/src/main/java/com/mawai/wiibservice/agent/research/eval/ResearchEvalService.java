package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.benchmark.BenchmarkCalculator;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibservice.agent.research.kline.KlineAggregator;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import com.mawai.wiibservice.agent.research.metrics.ReturnSeries;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;
import com.mawai.wiibservice.agent.research.series.MarketSeriesPoint;
import com.mawai.wiibservice.agent.research.series.MarketSeriesStore;
import com.mawai.wiibservice.agent.research.series.SeriesAligner;
import com.mawai.wiibservice.agent.research.series.SeriesCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 评估编排：从库加载 1m + 链下序列 → 调纯核心 evaluateBars（多策略同框）→ 写 target/ JSON。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchEvalService {

    private static final int LABEL_PURGE_HBARS = 1; // 标签前视=1 个 H-bar（决策周期=horizon）
    private static final BigDecimal NEUTRAL_FUNDING = BigDecimal.ZERO;            // 链下缺口中性：资金费=0
    private static final BigDecimal NEUTRAL_FEAR_GREED = BigDecimal.valueOf(50);  // 恐惧贪婪=50（中性）
    private static final BigDecimal NEUTRAL_ONCHAIN = BigDecimal.ZERO;            // 链上缺口中性：ETF 流入/稳定币差=0

    private final KlineHistoryStore store;
    private final MarketSeriesStore seriesStore;

    /** 一窗多预测器同框评估。链下/链上序列(funding/fng/etf/stablecoin)随 1m 一起按 [fromMs,toMs) 加载，逐决策点 as-of 对齐。 */
    public ComparisonReport evaluate(String symbol, ForecastHorizon horizon, long fromMs, long toMs,
                                     List<Forecaster> forecasters, EvalParams params) {
        List<KlineBar> oneMin = store.load(symbol, "1m", fromMs, toMs);
        List<MarketSeriesPoint> funding = seriesStore.load(symbol, SeriesCode.FUNDING, fromMs, toMs);
        List<MarketSeriesPoint> fearGreed = seriesStore.load(MarketSeriesStore.GLOBAL, SeriesCode.FEAR_GREED, fromMs, toMs);
        List<MarketSeriesPoint> etfFlow = seriesStore.load(symbol, SeriesCode.ETF_FLOW, fromMs, toMs);            // 仅 BTC 有；其他 symbol 空→中性
        List<MarketSeriesPoint> stablecoin = seriesStore.load(symbol, SeriesCode.STABLECOIN_DELTA, fromMs, toMs);
        ComparisonReport report = evaluateBars(symbol, horizon, oneMin, funding, fearGreed, etfFlow, stablecoin, forecasters, params);
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
        List<KlineBar> hbars = KlineAggregator.aggregate(oneMin, horizon.millis());
        int points = Math.max(0, hbars.size() - 1); // 每个决策点 i 需要 i+1 算实现收益

        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(
                points, params.testSize(), LABEL_PURGE_HBARS, params.embargoBars(), params.minTrain());

        // ---- 第一遍：装配各 test 决策点的 point-in-time 特征 + 市场原始收益（与策略无关，所有策略共享） ----
        List<ResearchFeatures> featuresPerPoint = new ArrayList<>();
        List<BigDecimal> rawReturns = new ArrayList<>();
        int firstTest = -1, lastTestExclusive = -1;
        for (WalkForwardWindow w : wins) {
            for (int i = w.testStart(); i < w.testEnd(); i++) {
                long ti = hbars.get(i).closeTime();                           // 决策"当下"=该 H-bar 收盘时刻
                double funding = SeriesAligner.asOf(fundingSeries, ti, NEUTRAL_FUNDING).doubleValue();
                int fng = SeriesAligner.asOf(fearGreedSeries, ti, NEUTRAL_FEAR_GREED).intValue();
                double etf = SeriesAligner.asOf(etfFlowSeries, ti, NEUTRAL_ONCHAIN).doubleValue();
                double stablecoin = SeriesAligner.asOf(stablecoinSeries, ti, NEUTRAL_ONCHAIN).doubleValue();
                featuresPerPoint.add(new ResearchFeatures(hbars.subList(0, i + 1), funding, fng, etf, stablecoin)); // 绝不含未来
                BigDecimal entry = hbars.get(i).close();
                BigDecimal next = hbars.get(i + 1).close();
                rawReturns.add(entry.signum() == 0 ? BigDecimal.ZERO
                        : next.subtract(entry).divide(entry, 10, RoundingMode.HALF_UP));
                if (firstTest < 0) firstTest = i;
                lastTestExclusive = i + 2; // 含 i+1 这根 close
            }
        }

        // ---- buy&hold：市场基准、与策略无关，算一次 ----
        BigDecimal buyHold = (firstTest >= 0 && lastTestExclusive <= hbars.size())
                ? BenchmarkCalculator.buyAndHoldReturn(hbars.subList(firstTest, lastTestExclusive))
                : BigDecimal.ZERO;

        // ---- 每预测器一条 StrategyLine（naive 分位依赖各自 positions，逐策略算） ----
        List<StrategyLine> lines = new ArrayList<>(forecasters.size());
        for (Forecaster fc : forecasters) {
            List<Integer> positions = new ArrayList<>(featuresPerPoint.size());
            List<BigDecimal> posReturns = new ArrayList<>(featuresPerPoint.size());
            for (int k = 0; k < featuresPerPoint.size(); k++) {
                int dir = fc.forecast(featuresPerPoint.get(k)).direction();
                positions.add(dir);
                posReturns.add(rawReturns.get(k).multiply(BigDecimal.valueOf(dir)));
            }
            ReturnSeries series = new ReturnSeries(fc.name(), posReturns, horizon.periodsPerYear());
            RiskAdjustedMetrics metrics = RiskAdjustedMetrics.from(series);
            double naivePct = positions.isEmpty() ? 0.0
                    : BenchmarkCalculator.permutationPercentile(positions, rawReturns, params.iterations(), params.seed());
            BigDecimal stratReturn = compound(posReturns);
            lines.add(new StrategyLine(fc.name(), metrics, stratReturn,
                    stratReturn.compareTo(buyHold) > 0, naivePct, naivePct >= params.naivePercentileThreshold(), series));
        }

        return new ComparisonReport(symbol, horizon.hours(), hbars.size(), featuresPerPoint.size(), buyHold, lines);
    }

    /** 复利净收益 = Π(1+r) − 1。 */
    private static BigDecimal compound(List<BigDecimal> returns) {
        BigDecimal eq = BigDecimal.ONE;
        for (BigDecimal r : returns) eq = eq.multiply(BigDecimal.ONE.add(r));
        return eq.subtract(BigDecimal.ONE);
    }

    private void writeReport(ComparisonReport report) {
        try {
            Path dir = Path.of("target", "research-eval");
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
