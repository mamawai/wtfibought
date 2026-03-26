package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.service.BankruptcyService;
import com.mawai.wiibservice.service.MarginAccountService;
import com.mawai.wiibservice.service.MarketDataService;
import com.mawai.wiibservice.service.AssetSnapshotService;
import com.mawai.wiibservice.task.MarketDataTask;
import com.mawai.wiibservice.task.ScheduledTasks;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

@Tag(name = "定时任务管理")
@RestController
@RequestMapping("/api/admin/task")
@RequiredArgsConstructor
public class TaskController {

    private final ScheduledTasks scheduledTasks;
    private final MarketDataTask marketDataTask;
    private final TradingConfig tradingConfig;
    private final BankruptcyService bankruptcyService;
    private final MarketDataService marketDataService;
    private final MarginAccountService marginAccountService;
    private final AssetSnapshotService assetSnapshotService;

    private void checkAdmin() {
        long userId = StpUtil.getLoginIdAsLong();
        if (userId != 1L) {
            throw new RuntimeException("无权限");
        }
    }

    @GetMapping("/status")
    @Operation(summary = "获取任务状态")
    public Result<Map<String, Object>> getStatus() {
        checkAdmin();
        return Result.ok(scheduledTasks.getTaskStatus());
    }

    @PostMapping("/market-push/start")
    @Operation(summary = "启动行情推送")
    public Result<Void> startMarketPush() {
        checkAdmin();
        scheduledTasks.startMarketDataPush();
        return Result.ok();
    }

    @PostMapping("/market-push/stop")
    @Operation(summary = "停止行情推送")
    public Result<Void> stopMarketPush() {
        checkAdmin();
        scheduledTasks.stopMarketDataPush();
        return Result.ok();
    }

    @PostMapping("/settlement/start")
    @Operation(summary = "启动结算任务")
    public Result<Void> startSettlement() {
        checkAdmin();
        scheduledTasks.startSettlementTask();
        return Result.ok();
    }

    @PostMapping("/settlement/stop")
    @Operation(summary = "停止结算任务")
    public Result<Void> stopSettlement() {
        checkAdmin();
        scheduledTasks.stopSettlementTask();
        return Result.ok();
    }

    @PostMapping("/expire-orders")
    @Operation(summary = "执行过期订单处理")
    public Result<Void> expireOrders() {
        checkAdmin();
        scheduledTasks.expireLimitOrders();
        return Result.ok();
    }

    @PostMapping("/generate-data")
    @Operation(summary = "生成行情数据（offset: 0=当天, 1=明天, -1=昨天）")
    public Result<Void> generateData(@RequestParam(defaultValue = "1") int offset) {
        checkAdmin();
        marketDataTask.generateData(offset);
        return Result.ok();
    }

    @PostMapping("/load-redis")
    @Operation(summary = "加载今日行情到Redis")
    public Result<Void> loadRedis() {
        checkAdmin();
        marketDataTask.loadTodayDataToRedis();
        return Result.ok();
    }

    @PostMapping("/refresh-stock-cache")
    @Operation(summary = "重建当日Stock汇总缓存（按ticks，截止当前时间）")
    public Result<Map<String, Object>> refreshStockCache(
        @RequestParam(required = false) String date,
        @RequestParam(required = false) String time
    ) {
        checkAdmin();
        LocalDate targetDate = date != null && !date.isBlank() ? LocalDate.parse(date) : LocalDate.now();
        LocalTime asOfTime = time != null && !time.isBlank() ? LocalTime.parse(time) : LocalTime.now();
        return Result.ok(marketDataService.refreshDailyCacheFromTicks(targetDate, asOfTime));
    }

    @PostMapping("/bankruptcy/check")
    @Operation(summary = "执行爆仓检查（并清算触发爆仓的用户）")
    public Result<Void> checkBankruptcy() {
        checkAdmin();
        bankruptcyService.checkAndLiquidateAll();
        return Result.ok();
    }

    @PostMapping("/margin/accrue-interest")
    @Operation(summary = "手动执行杠杆计息")
    public Result<Void> accrueInterest() {
        checkAdmin();
        marginAccountService.accrueDailyInterest(LocalDate.now());
        return Result.ok();
    }

    @GetMapping("/margin/daily-interest-rate")
    @Operation(summary = "获取杠杆日利率（decimal，例如0.0005=0.05%/天）")
    public Result<BigDecimal> getDailyInterestRate() {
        checkAdmin();
        return Result.ok(tradingConfig.getMargin().getDailyInterestRate());
    }

    @PostMapping("/margin/daily-interest-rate")
    @Operation(summary = "修改杠杆日利率（decimal，例如0.0005=0.05%/天）")
    public Result<BigDecimal> setDailyInterestRate(@RequestBody DailyInterestRateRequest request) {
        checkAdmin();
        if (request == null || request.getDailyInterestRate() == null) {
            throw new IllegalArgumentException("dailyInterestRate不能为空");
        }
        BigDecimal rate = request.getDailyInterestRate();
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("dailyInterestRate必须在[0,1]范围内");
        }

        BigDecimal normalized = rate.stripTrailingZeros();
        if (normalized.scale() > 12) {
            normalized = normalized.setScale(12, RoundingMode.HALF_UP).stripTrailingZeros();
        }

        tradingConfig.getMargin().setDailyInterestRate(normalized);
        return Result.ok(tradingConfig.getMargin().getDailyInterestRate());
    }

    @PostMapping("/asset-snapshot")
    @Operation(summary = "手动触发每日资产快照")
    public Result<Void> assetSnapshot() {
        checkAdmin();
        assetSnapshotService.snapshotAll();
        return Result.ok();
    }

    @Data
    public static class DailyInterestRateRequest {
        private BigDecimal dailyInterestRate;
    }
}
