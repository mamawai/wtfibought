package com.mawai.wiibquant.controller;

import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.research.ForecastHorizon;
import com.mawai.wiibquant.research.eval.ComparisonReport;
import com.mawai.wiibquant.research.eval.EvalParams;
import com.mawai.wiibquant.research.eval.ResearchEvalService;
import com.mawai.wiibquant.research.forecast.ContinuousFactorForecaster;
import com.mawai.wiibquant.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibquant.research.forecast.Forecaster;
import com.mawai.wiibcommon.market.KlineHistoryStore;
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
@RequireAdmin // 整个研究/评估控制器仅管理员(userId=1)可访问
public class ResearchEvalController {

    private final KlineHistoryStore store;
    private final ResearchEvalService evalService;

    /** 回填默认 5m K 线：最近 fromDays 天。 */
    @PostMapping("/backfill")
    public Result<Integer> backfill(@RequestParam(defaultValue = "BTCUSDT") String symbol,
                                    @RequestParam(defaultValue = "180") int fromDays) {
        long now = System.currentTimeMillis();
        long from = now - fromDays * 24L * 3600_000L;
        return Result.ok(store.backfill(symbol, from, now));
    }

    /** 跑一次样本外评估：EWMA 基线 vs 连续因子，同框出 ComparisonReport。 */
    @PostMapping("/run")
    public Result<ComparisonReport> run(@RequestParam(defaultValue = "BTCUSDT") String symbol,
                                        @RequestParam(defaultValue = "12") int horizonHours,
                                        @RequestParam(defaultValue = "180") int fromDays) {
        long now = System.currentTimeMillis();
        long from = now - fromDays * 24L * 3600_000L;
        List<Forecaster> forecasters = List.of(
                new EwmaMomentumForecaster(12, 26),     // 价格基线
                ContinuousFactorForecaster.defaults());  // 训练窗学习连续因子方向（样本外检验）
        ComparisonReport report = evalService.evaluate(
                symbol, ForecastHorizon.fromHours(horizonHours), from, now, forecasters, EvalParams.defaults());
        return Result.ok(report);
    }
}
