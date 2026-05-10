package com.mawai.wiibservice.controller;

import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.quant.service.FactorWeightReplayService;
import com.mawai.wiibservice.agent.trading.BacktestComparisonResult;
import com.mawai.wiibservice.agent.trading.BacktestResult;
import com.mawai.wiibservice.agent.trading.BacktestRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回测 API — 在历史数据上验证 DeterministicTradingExecutor 策略表现。
 */
@Slf4j
@Tag(name = "策略回测")
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestRunner backtestRunner;
    private final FactorWeightReplayService factorWeightReplayService;

    @Operation(summary = "运行回测")
    @PostMapping("/run")
    public Result<Map<String, Object>> runBacktest(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "100000") BigDecimal initialBalance) {

        log.info("[Backtest API] symbol={} days={} balance={}", symbol, days, initialBalance);

        try {
            BacktestResult result = backtestRunner.runBacktest(symbol, days, initialBalance);
            return Result.ok(toMap(result));
        } catch (Exception e) {
            log.error("[Backtest API] 回测失败", e);
            return Result.fail("回测失败: " + e.getMessage());
        }
    }

    @Operation(summary = "参数扫描")
    @PostMapping("/scan")
    public Result<List<Map<String, Object>>> parameterScan(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "100000") BigDecimal initialBalance) {

        log.info("[Backtest API] 参数扫描 symbol={} days={}", symbol, days);

        try {
            List<BacktestRunner.ScanResult> results =
                    backtestRunner.parameterScan(symbol, days, initialBalance);

            List<Map<String, Object>> response = results.stream().map(sr -> {
                Map<String, Object> map = toMap(sr.result());
                map.put("slAtr", sr.slAtr());
                map.put("tpAtr", sr.tpAtr());
                return map;
            }).toList();

            return Result.ok(response);
        } catch (Exception e) {
            log.error("[Backtest API] 参数扫描失败", e);
            return Result.fail("参数扫描失败: " + e.getMessage());
        }
    }

    @Operation(summary = "新旧退出引擎 K 线回测对比")
    @PostMapping("/compare")
    public Result<Map<String, Object>> compareBacktest(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "100000") BigDecimal initialBalance) {

        log.info("[Backtest API] compare symbol={} days={} balance={}", symbol, days, initialBalance);

        try {
            BacktestComparisonResult report = backtestRunner.compareBacktest(symbol, days, initialBalance);
            return Result.ok(toMap(report));
        } catch (Exception e) {
            log.error("[Backtest API] 新旧引擎K线对比失败", e);
            return Result.fail("新旧引擎K线对比失败: " + e.getMessage());
        }
    }

    @Operation(summary = "信号回放回测 — 基于数据库真实历史信号，和实盘100%等价")
    @PostMapping("/replay")
    public Result<Map<String, Object>> runReplay(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "100000") BigDecimal initialBalance) {

        log.info("[Backtest API] replay symbol={} from={} to={} balance={}",
                symbol, from, to, initialBalance);

        try {
            LocalDateTime fromDt = parseDateTime(from);
            LocalDateTime toDt = parseDateTime(to);
            BacktestResult result = backtestRunner.runReplay(symbol, fromDt, toDt, initialBalance);
            return Result.ok(toMap(result));
        } catch (Exception e) {
            log.error("[Backtest API] 回放失败", e);
            return Result.fail("回放失败: " + e.getMessage());
        }
    }

    @Operation(summary = "新旧退出引擎信号回放对比 — 基于数据库真实历史信号")
    @PostMapping("/replay/compare")
    public Result<Map<String, Object>> compareReplay(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "100000") BigDecimal initialBalance) {

        log.info("[Backtest API] replay compare symbol={} from={} to={} balance={}",
                symbol, from, to, initialBalance);

        try {
            LocalDateTime fromDt = parseDateTime(from);
            LocalDateTime toDt = parseDateTime(to);
            BacktestComparisonResult report =
                    backtestRunner.compareReplay(symbol, fromDt, toDt, initialBalance);
            return Result.ok(toMap(report));
        } catch (Exception e) {
            log.error("[Backtest API] 新旧引擎信号回放对比失败", e);
            return Result.fail("新旧引擎信号回放对比失败: " + e.getMessage());
        }
    }

    @Operation(summary = "B1-3 静态调权只读回放 — 重新计算历史AgentVote方向命中率")
    @PostMapping("/factor-weight-replay")
    public Result<FactorWeightReplayService.ReplayReport> runFactorWeightReplay(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam String from,
            @RequestParam String to) {

        log.info("[Backtest API] factor-weight-replay symbol={} from={} to={}", symbol, from, to);

        try {
            LocalDateTime fromDt = parseDateTime(from);
            LocalDateTime toDt = parseDateTime(to);
            FactorWeightReplayService.ReplayReport report =
                    factorWeightReplayService.replay(symbol, fromDt, toDt);
            return Result.ok(report);
        } catch (Exception e) {
            log.error("[Backtest API] 静态调权回放失败", e);
            return Result.fail("静态调权回放失败: " + e.getMessage());
        }
    }

    /** 接受 "2026-04-01" 或 "2026-04-01T10:00:00" 两种格式 */
    private LocalDateTime parseDateTime(String s) {
        if (s.contains("T")) return LocalDateTime.parse(s);
        return LocalDateTime.parse(s + "T00:00:00");
    }

    private Map<String, Object> toMap(BacktestResult r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalTrades", r.totalTrades());
        map.put("wins", r.wins());
        map.put("losses", r.losses());
        map.put("winRate", r.winRate());
        map.put("profitFactor", r.profitFactor());
        map.put("netProfit", r.netProfit());
        map.put("totalFees", r.totalFees());
        map.put("sharpeRatio", r.sharpeRatio());
        map.put("maxDrawdownPct", r.maxDrawdownPct());
        map.put("avgHoldBars", r.avgHoldBars());
        map.put("avgR", r.avgR());
        map.put("returnPct", r.returnPct());
        map.put("finalEquity", r.finalEquity());

        Map<String, Object> byStrategy = new LinkedHashMap<>();
        for (BacktestResult.StrategyStats stats : r.allStrategyStats()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("trades", stats.trades());
            item.put("wins", stats.wins());
            item.put("losses", stats.losses());
            item.put("winRate", stats.winRate());
            item.put("netPnl", stats.netPnl());
            item.put("ev", stats.ev());
            item.put("avgR", stats.avgR());
            item.put("profitFactor", stats.profitFactor());
            item.put("maxDrawdownPct", stats.maxDrawdownPct());
            item.put("avgHoldBars", stats.avgHoldBars());
            item.put("maxConsecutiveLosses", stats.maxConsecutiveLosses());
            byStrategy.put(stats.strategy(), item);
        }
        map.put("byStrategy", byStrategy);

        return map;
    }

    private Map<String, Object> toMap(BacktestComparisonResult r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("symbol", r.symbol());
        map.put("mode", r.mode());
        map.put("legacy", toMap(r.legacy()));
        map.put("playbook", toMap(r.playbook()));
        map.put("overallDelta", toMap(r.overallDelta()));
        map.put("tradeDiffSummary", toMap(r.tradeDiffSummary()));
        map.put("strategyDeltas", r.strategyDeltas().stream().map(this::toMap).toList());
        map.put("tradeDiffs", r.tradeDiffs().stream().map(this::toMap).toList());
        return map;
    }

    private Map<String, Object> toMap(BacktestComparisonResult.OverallDelta d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalTradesDelta", d.totalTradesDelta());
        map.put("netProfitDelta", d.netProfitDelta());
        map.put("returnPctDelta", d.returnPctDelta());
        map.put("winRateDelta", d.winRateDelta());
        map.put("avgRDelta", d.avgRDelta());
        map.put("maxDrawdownPctDelta", d.maxDrawdownPctDelta());
        map.put("avgHoldBarsDelta", d.avgHoldBarsDelta());
        return map;
    }

    private Map<String, Object> toMap(BacktestComparisonResult.StrategyDelta d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("strategy", d.strategy());
        map.put("legacyTrades", d.legacyTrades());
        map.put("playbookTrades", d.playbookTrades());
        map.put("tradesDelta", d.tradesDelta());
        map.put("netPnlDelta", d.netPnlDelta());
        map.put("winRateDelta", d.winRateDelta());
        map.put("avgRDelta", d.avgRDelta());
        map.put("maxDrawdownPctDelta", d.maxDrawdownPctDelta());
        map.put("avgHoldBarsDelta", d.avgHoldBarsDelta());
        map.put("legacyLosses", d.legacyLosses());
        map.put("playbookLosses", d.playbookLosses());
        map.put("lossesDelta", d.lossesDelta());
        return map;
    }

    private Map<String, Object> toMap(BacktestComparisonResult.TradeDiffSummary s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("matchedTrades", s.matchedTrades());
        map.put("legacyOnlyTrades", s.legacyOnlyTrades());
        map.put("playbookOnlyTrades", s.playbookOnlyTrades());
        map.put("playbookEarlierTrades", s.playbookEarlierTrades());
        map.put("playbookLaterTrades", s.playbookLaterTrades());
        map.put("sameCloseBarTrades", s.sameCloseBarTrades());
        map.put("avgCloseBarDelta", s.avgCloseBarDelta());
        map.put("avgPnlDelta", s.avgPnlDelta());
        map.put("legacyBreakoutLosingTrades", s.legacyBreakoutLosingTrades());
        map.put("playbookBreakoutLosingTrades", s.playbookBreakoutLosingTrades());
        map.put("breakoutLosingPnlDelta", s.breakoutLosingPnlDelta());
        map.put("breakoutRunnerImprovedTrades", s.breakoutRunnerImprovedTrades());
        map.put("mrAvgHoldBarsDelta", s.mrAvgHoldBarsDelta());
        map.put("trendAvgHoldBarsDelta", s.trendAvgHoldBarsDelta());
        return map;
    }

    private Map<String, Object> toMap(BacktestComparisonResult.TradeDiff d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", d.status());
        map.put("matchKey", d.matchKey());
        map.put("strategy", d.strategy());
        map.put("side", d.side());
        map.put("entryBarIndex", d.entryBarIndex());
        map.put("legacyCloseBarIndex", d.legacyCloseBarIndex());
        map.put("playbookCloseBarIndex", d.playbookCloseBarIndex());
        map.put("closeBarDelta", d.closeBarDelta());
        map.put("legacyPnl", d.legacyPnl());
        map.put("playbookPnl", d.playbookPnl());
        map.put("pnlDelta", d.pnlDelta());
        map.put("legacyR", d.legacyR());
        map.put("playbookR", d.playbookR());
        map.put("rDelta", d.rDelta());
        map.put("legacyExitReason", d.legacyExitReason());
        map.put("playbookExitReason", d.playbookExitReason());
        return map;
    }
}
