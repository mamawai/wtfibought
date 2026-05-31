package com.mawai.wiibservice.controller;

import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.research.ForecastHorizon;
import com.mawai.wiibservice.agent.research.eval.EvalParams;
import com.mawai.wiibservice.agent.research.eval.EvalReport;
import com.mawai.wiibservice.agent.research.eval.ResearchEvalService;
import com.mawai.wiibservice.agent.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibservice.agent.research.kline.KlineHistoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 研究/评估触发入口：离线按需跑，绝不接 live 周期。 */
@RestController
@RequestMapping("/api/research/eval")
@RequiredArgsConstructor
public class ResearchEvalController {

    private final KlineHistoryStore store;
    private final ResearchEvalService evalService;

    /** 回填 1m K 线：最近 fromDays 天。 */
    @PostMapping("/backfill")
    public Result<Integer> backfill(@RequestParam(defaultValue = "BTCUSDT") String symbol,
                                    @RequestParam(defaultValue = "180") int fromDays) {
        long now = System.currentTimeMillis();
        long from = now - fromDays * 24L * 3600_000L;
        return Result.ok(store.backfill(symbol, from, now));
    }

    /** 跑一次样本外评估（EWMA 动量基线）。 */
    @PostMapping("/run")
    public Result<EvalReport> run(@RequestParam(defaultValue = "BTCUSDT") String symbol,
                                  @RequestParam(defaultValue = "12") int horizonHours,
                                  @RequestParam(defaultValue = "180") int fromDays) {
        long now = System.currentTimeMillis();
        long from = now - fromDays * 24L * 3600_000L;
        EvalReport report = evalService.evaluate(
                symbol, ForecastHorizon.fromHours(horizonHours), from, now,
                new EwmaMomentumForecaster(12, 26), EvalParams.defaults());
        return Result.ok(report);
    }
}
