package com.mawai.wiibservice.controller;

import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.eval.ComparisonReport;
import com.mawai.wiibservice.agent.research.eval.EvalParams;
import com.mawai.wiibservice.agent.research.eval.ResearchEvalService;
import com.mawai.wiibservice.agent.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibservice.agent.research.forecast.Forecaster;
import com.mawai.wiibservice.agent.research.forecast.MultiFactorForecaster;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import com.mawai.wiibservice.agent.research.series.MarketSeriesStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 研究/评估触发入口：离线按需跑，绝不接 live 周期。 */
@RestController
@RequestMapping("/api/research/eval")
@RequiredArgsConstructor
public class ResearchEvalController {

    private final KlineHistoryStore store;
    private final MarketSeriesStore seriesStore;
    private final ResearchEvalService evalService;

    /** 回填 1m K 线：最近 fromDays 天。 */
    @PostMapping("/backfill")
    public Result<Integer> backfill(@RequestParam(defaultValue = "BTCUSDT") String symbol,
                                    @RequestParam(defaultValue = "180") int fromDays) {
        long now = System.currentTimeMillis();
        long from = now - fromDays * 24L * 3600_000L;
        return Result.ok(store.backfill(symbol, from, now));
    }

    /** 回填链下序列：资金费(按 symbol，endTime 翻页) + 恐惧贪婪(全市场，一次拉全)。 */
    @PostMapping("/backfill-series")
    public Result<String> backfillSeries(@RequestParam(defaultValue = "BTCUSDT") String symbol,
                                         @RequestParam(defaultValue = "180") int fromDays) {
        long now = System.currentTimeMillis();
        long from = now - fromDays * 24L * 3600_000L;
        int funding = seriesStore.backfillFunding(symbol, from, now);
        int fng = seriesStore.backfillFearGreed(0); // 0=全部历史(日级，一次拉全)
        return Result.ok(String.format("funding=%d, fearGreed=%d", funding, fng));
    }

    /** 跑一次样本外评估：EWMA 基线 vs 多因子，同框出 ComparisonReport（四线）。 */
    @PostMapping("/run")
    public Result<ComparisonReport> run(@RequestParam(defaultValue = "BTCUSDT") String symbol,
                                        @RequestParam(defaultValue = "12") int horizonHours,
                                        @RequestParam(defaultValue = "180") int fromDays) {
        long now = System.currentTimeMillis();
        long from = now - fromDays * 24L * 3600_000L;
        List<Forecaster> forecasters = List.of(
                new EwmaMomentumForecaster(12, 26), MultiFactorForecaster.defaults());
        ComparisonReport report = evalService.evaluate(
                symbol, ForecastHorizon.fromHours(horizonHours), from, now, forecasters, EvalParams.defaults());
        return Result.ok(report);
    }
}
