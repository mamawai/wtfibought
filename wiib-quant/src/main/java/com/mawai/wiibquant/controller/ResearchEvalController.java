package com.mawai.wiibquant.controller;

import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.agent.external.etf.EtfFlowScraper;
import com.mawai.wiibquant.agent.quant.service.StablecoinFlowService;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import com.mawai.wiibquant.agent.research.eval.ComparisonReport;
import com.mawai.wiibquant.agent.research.eval.EvalParams;
import com.mawai.wiibquant.agent.research.eval.ResearchEvalService;
import com.mawai.wiibquant.agent.research.forecast.ContinuousFactorForecaster;
import com.mawai.wiibquant.agent.research.forecast.EwmaMomentumForecaster;
import com.mawai.wiibquant.agent.research.forecast.Forecaster;
import com.mawai.wiibquant.agent.research.forecast.MultiFactorForecaster;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.agent.research.series.MarketSeriesStore;
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
    private final EtfFlowScraper etfFlowScraper;
    private final StablecoinFlowService stablecoinFlowService;
    private final ResearchEvalService evalService;

    /** 回填默认 5m K 线：最近 fromDays 天。 */
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

    /** 回填链上序列全历史：BTC ETF 净流入(Farside) + 稳定币供给差(DeFiLlama)；均一次拉全历史，无需 symbol/days。 */
    @PostMapping("/backfill-onchain")
    public Result<String> backfillOnchain() {
        int etf = etfFlowScraper.backfillHistory();
        int stablecoin = stablecoinFlowService.backfillHistory();
        return Result.ok(String.format("etfFlow=%d, stablecoin=%d", etf, stablecoin));
    }

    /** 跑一次样本外评估：EWMA 基线 vs 多因子，同框出 ComparisonReport（四线）。 */
    @PostMapping("/run")
    public Result<ComparisonReport> run(@RequestParam(defaultValue = "BTCUSDT") String symbol,
                                        @RequestParam(defaultValue = "12") int horizonHours,
                                        @RequestParam(defaultValue = "180") int fromDays) {
        long now = System.currentTimeMillis();
        long from = now - fromDays * 24L * 3600_000L;
        List<Forecaster> forecasters = List.of(
                new EwmaMomentumForecaster(12, 26),     // 价格基线
                MultiFactorForecaster.defaults(),       // 原 3 腿（趋势+资金费+恐惧贪婪）
                MultiFactorForecaster.onChainOnly(),    // 纯链上 2 腿（ETF+稳定币）：测独立 edge
                MultiFactorForecaster.allFactors(),     // 全 5 腿：测链上叠加的边际增益
                ContinuousFactorForecaster.defaults());  // 训练窗学习连续因子方向（样本外检验）
        ComparisonReport report = evalService.evaluate(
                symbol, ForecastHorizon.fromHours(horizonHours), from, now, forecasters, EvalParams.defaults());
        return Result.ok(report);
    }
}
