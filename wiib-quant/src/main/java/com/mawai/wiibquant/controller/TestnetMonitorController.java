package com.mawai.wiibquant.controller;

import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.agent.binance.model.UserTrade;
import com.mawai.wiibquant.agent.strategy.monitor.TestnetMonitorService;
import com.mawai.wiibquant.agent.strategy.monitor.dto.DailyCell;
import com.mawai.wiibquant.agent.strategy.monitor.dto.EquityPoint;
import com.mawai.wiibquant.agent.strategy.monitor.dto.FillStats;
import com.mawai.wiibquant.agent.strategy.monitor.dto.OverviewView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Binance Testnet 模拟盘监测看板 API。
 * 数据全部实时直拉 testnet（仅交易走 testnet，行情仍主网），用于验证 fibo 策略能否跑通/赚钱。
 */
@Slf4j
@RestController
@RequestMapping("/api/testnet")
@RequiredArgsConstructor
public class TestnetMonitorController {

    private final TestnetMonitorService monitorService;

    /** 实时总览：账户 + 持仓 + 挂单。 */
    @GetMapping("/overview")
    public Result<OverviewView> overview() {
        return Result.ok(monitorService.overview());
    }

    /** 交易记录（近 days 天真实成交，倒序）。 */
    @GetMapping("/trades")
    public Result<List<UserTrade>> trades(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "30") int days) {
        return Result.ok(monitorService.trades(symbol, days));
    }

    /** 日交易网格（近 days 天按天聚合盈亏）。 */
    @GetMapping("/daily-grid")
    public Result<List<DailyCell>> dailyGrid(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "90") int days) {
        return Result.ok(monitorService.dailyGrid(symbol, days));
    }

    /** 权益曲线（近 days 天累计已实现盈亏）。 */
    @GetMapping("/equity")
    public Result<List<EquityPoint>> equity(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "90") int days) {
        return Result.ok(monitorService.equity(symbol, days));
    }

    /** fill 对账（近 days 天进场单成交质量）。 */
    @GetMapping("/fill-stats")
    public Result<FillStats> fillStats(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "30") int days) {
        return Result.ok(monitorService.fillStats(symbol, days));
    }
}
