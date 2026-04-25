package com.mawai.wiibservice.controller;

import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.quant.service.FactorWeightReplayService;
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
        map.put("returnPct", r.returnPct());
        map.put("finalEquity", r.finalEquity());

        Map<String, Integer> byStrategy = new LinkedHashMap<>();
        byStrategy.put("LEGACY_TREND", r.tradesByStrategy("LEGACY_TREND").size());
        byStrategy.put("MR", r.tradesByStrategy("MR").size());
        byStrategy.put("BREAKOUT", r.tradesByStrategy("BREAKOUT").size());
        map.put("byStrategy", byStrategy);

        return map;
    }
}
