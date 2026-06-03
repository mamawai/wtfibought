package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.benchmark.BenchmarkCalculator;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibservice.agent.research.forecast.TrainingSample;
import com.mawai.wiibservice.agent.research.kline.KlineAggregator;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import com.mawai.wiibservice.agent.research.label.BarrierLabel;
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

    // 特征/波动率回看窗：每点只喂最近 W 根，杜绝 forecast 每次对整段历史重算指标（O(points²)→O(points·W)）。
    // W=256 精确覆盖 ma_alignment 的 MA99；EMA26/EWMA 波动率是种子衰减后的可控近似，用回归测试守住现有研究口径。
    private static final int FEATURE_LOOKBACK_BARS = 256;

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
        return evaluateBars(symbol, horizon, oneMin, fundingSeries, fearGreedSeries, etfFlowSeries,
                stablecoinSeries, forecasters, params, FEATURE_LOOKBACK_BARS);
    }

    /** 同上；回看窗 W 可注入——测试可传 Integer.MAX_VALUE 跑"全历史"对照。生产恒走默认 W。 */
    static ComparisonReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                         List<MarketSeriesPoint> fundingSeries,
                                         List<MarketSeriesPoint> fearGreedSeries,
                                         List<MarketSeriesPoint> etfFlowSeries,
                                         List<MarketSeriesPoint> stablecoinSeries,
                                         List<Forecaster> forecasters, EvalParams params, int featureLookbackBars) {
        List<KlineBar> hbars = KlineAggregator.aggregate(oneMin, horizon.millis());
        int points = Math.max(0, hbars.size() - 1); // 每个决策点 i 需要 i+1 算实现收益

        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(
                points, params.testSize(), LABEL_PURGE_HBARS, params.embargoBars(), params.minTrain());

        // ---- 第一遍：装配每个决策点的 point-in-time 特征 + 三隔栏训练目标；后面按 window 只把 train 交给 fit ----
        List<ResearchFeatures> featuresByPoint = new ArrayList<>(points);
        List<BigDecimal> longBarrierReturnsByPoint = new ArrayList<>(points);
        List<TrainingSample> samplesByPoint = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            long ti = hbars.get(i).closeTime();                           // 决策"当下"=该 H-bar 收盘时刻
            double funding = SeriesAligner.asOf(fundingSeries, ti, NEUTRAL_FUNDING).doubleValue();
            int fng = SeriesAligner.asOf(fearGreedSeries, ti, NEUTRAL_FEAR_GREED).intValue();
            double etf = SeriesAligner.asOf(etfFlowSeries, ti, NEUTRAL_ONCHAIN).doubleValue();
            double stablecoin = SeriesAligner.asOf(stablecoinSeries, ti, NEUTRAL_ONCHAIN).doubleValue();
            int lookbackFrom = Math.max(0, i + 1 - featureLookbackBars); // 回看窗起点：晚期点恒 W 根，早期点不足则从 0（暖机语义不变）
            ResearchFeatures features = new ResearchFeatures(hbars.subList(lookbackFrom, i + 1), funding, fng, etf, stablecoin); // 绝不含未来
            BigDecimal entry = hbars.get(i).close();
            List<KlineBar> path = pathBars(oneMin, ti, horizon.millis());
            double sigma = VolatilityEstimator.ewmaVolatility(hbars.subList(lookbackFrom, i + 1), params.lambda());
            BarrierLabel label = sigma > 0
                    ? TripleBarrierLabeler.label(entry, params.k(), sigma, path)
                    : BarrierLabel.VERTICAL;
            BigDecimal fallbackClose = path.isEmpty() ? hbars.get(i + 1).close() : path.get(path.size() - 1).close();
            BigDecimal longReturn = longReturn(entry, fallbackClose, label, params.k(), sigma);
            featuresByPoint.add(features);
            longBarrierReturnsByPoint.add(longReturn);
            samplesByPoint.add(new TrainingSample(features, longReturn));
        }

        int firstTest = -1, lastTestExclusive = -1, testPointCount = 0;
        for (WalkForwardWindow w : wins) {
            if (firstTest < 0) firstTest = w.testStart();
            lastTestExclusive = w.testEnd() + 1; // 含最后一个 test 决策点的下一根 close
            testPointCount += w.testSize();
        }

        // ---- buy&hold：市场基准、与策略无关，算一次 ----
        BigDecimal buyHold = (firstTest >= 0 && lastTestExclusive <= hbars.size())
                ? BenchmarkCalculator.buyAndHoldReturn(hbars.subList(firstTest, lastTestExclusive))
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

        return new ComparisonReport(symbol, horizon.hours(), hbars.size(), testPointCount, buyHold, lines);
    }

    /**
     * 决策在 H-bar 收盘后发生，三隔栏必须只看之后 1 个 horizon 内的 1m 路径。
     * 用二分定位首根 openTime > decisionCloseTime，避免长历史每个决策点都全表扫描。
     */
    private static List<KlineBar> pathBars(List<KlineBar> oneMin, long decisionCloseTime, long horizonMillis) {
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
    private static BigDecimal compound(List<BigDecimal> returns) {
        BigDecimal eq = BigDecimal.ONE;
        for (BigDecimal r : returns) eq = eq.multiply(BigDecimal.ONE.add(r));
        return eq.subtract(BigDecimal.ONE);
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
