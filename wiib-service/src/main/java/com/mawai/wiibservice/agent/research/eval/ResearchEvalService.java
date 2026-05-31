package com.mawai.wiibservice.agent.research.eval;

import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.benchmark.BenchmarkCalculator;
import com.mawai.wiibservice.agent.research.forecast.Forecast;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.kline.KlineAggregator;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import com.mawai.wiibservice.agent.research.metrics.ReturnSeries;
import com.mawai.wiibservice.agent.research.metrics.RiskAdjustedMetrics;
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

/** 评估编排：从库加载 1m → 调纯核心 evaluateBars → 写 target/ JSON。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchEvalService {

    private static final int LABEL_PURGE_HBARS = 1; // 标签前视=1 个 H-bar（决策周期=horizon）

    private final KlineHistoryStore store;

    public EvalReport evaluate(String symbol, ForecastHorizon horizon, long fromMs, long toMs,
                               Forecaster forecaster, EvalParams params) {
        List<KlineBar> oneMin = store.load(symbol, "1m", fromMs, toMs);
        EvalReport report = evaluateBars(symbol, horizon, oneMin, forecaster, params);
        writeReport(report);
        return report;
    }

    /** 纯核心（无 DB，可单测）：聚合→逐决策点预测+模拟收益→walk-forward 取样本外→指标+双基准→报告。 */
    public static EvalReport evaluateBars(String symbol, ForecastHorizon horizon, List<KlineBar> oneMin,
                                          Forecaster forecaster, EvalParams params) {
        List<KlineBar> hbars = KlineAggregator.aggregate(oneMin, horizon.millis());
        int points = Math.max(0, hbars.size() - 1); // 每个决策点 i 需要 i+1 算实现收益

        List<WalkForwardWindow> wins = WalkForwardEvaluator.windows(
                points, params.testSize(), LABEL_PURGE_HBARS, params.embargoBars(), params.minTrain());

        List<Integer> positions = new ArrayList<>();
        List<BigDecimal> rawReturns = new ArrayList<>();
        List<BigDecimal> posReturns = new ArrayList<>();
        int firstTest = -1, lastTestExclusive = -1;

        for (WalkForwardWindow w : wins) {
            for (int i = w.testStart(); i < w.testEnd(); i++) {
                List<KlineBar> hist = hbars.subList(0, i + 1); // point-in-time，绝不含未来
                Forecast f = forecaster.forecast(hist);
                BigDecimal entry = hbars.get(i).close();
                BigDecimal next = hbars.get(i + 1).close();
                BigDecimal raw = entry.signum() == 0 ? BigDecimal.ZERO
                        : next.subtract(entry).divide(entry, 10, RoundingMode.HALF_UP);
                positions.add(f.direction());
                rawReturns.add(raw);
                posReturns.add(raw.multiply(BigDecimal.valueOf(f.direction())));
                if (firstTest < 0) firstTest = i;
                lastTestExclusive = i + 2; // 含 i+1 这根 close
            }
        }

        ReturnSeries series = new ReturnSeries(forecaster.name(), posReturns, horizon.periodsPerYear());
        RiskAdjustedMetrics metrics = RiskAdjustedMetrics.from(series);
        double naivePct = positions.isEmpty() ? 0.0
                : BenchmarkCalculator.permutationPercentile(positions, rawReturns, params.iterations(), params.seed());
        BigDecimal buyHold = (firstTest >= 0 && lastTestExclusive <= hbars.size())
                ? BenchmarkCalculator.buyAndHoldReturn(hbars.subList(firstTest, lastTestExclusive))
                : BigDecimal.ZERO;
        BigDecimal stratReturn = compound(posReturns);

        boolean beatBH = stratReturn.compareTo(buyHold) > 0;
        boolean beatNaive = naivePct >= params.naivePercentileThreshold();

        return new EvalReport(symbol, horizon.hours(), hbars.size(), posReturns.size(),
                metrics, buyHold, stratReturn, naivePct, beatBH, beatNaive, series);
    }

    /** 复利净收益 = Π(1+r) − 1。 */
    private static BigDecimal compound(List<BigDecimal> returns) {
        BigDecimal eq = BigDecimal.ONE;
        for (BigDecimal r : returns) eq = eq.multiply(BigDecimal.ONE.add(r));
        return eq.subtract(BigDecimal.ONE);
    }

    private void writeReport(EvalReport report) {
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
