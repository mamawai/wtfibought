package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.MarginAccountService;
import com.mawai.wiibsim.service.AssetSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Tag(name = "定时任务管理")
@RestController
@RequestMapping("/api/admin/task")
@RequiredArgsConstructor
@RequireAdmin // 整个任务管理控制器仅管理员(userId=1)可访问
public class TaskController {

    private final TradingConfig tradingConfig;
    private final BankruptcyService bankruptcyService;
    private final MarginAccountService marginAccountService;
    private final AssetSnapshotService assetSnapshotService;

    @PostMapping("/bankruptcy/check")
    @Operation(summary = "执行爆仓检查（并清算触发爆仓的用户）")
    public Result<Void> checkBankruptcy() {
        bankruptcyService.checkAndLiquidateAll();
        return Result.ok();
    }

    @PostMapping("/margin/accrue-interest")
    @Operation(summary = "手动执行杠杆计息")
    public Result<Void> accrueInterest() {
        marginAccountService.accrueDailyInterest(LocalDate.now());
        return Result.ok();
    }

    @GetMapping("/margin/daily-interest-rate")
    @Operation(summary = "获取杠杆日利率（decimal，例如0.0005=0.05%/天）")
    public Result<BigDecimal> getDailyInterestRate() {
        return Result.ok(tradingConfig.getMargin().getDailyInterestRate());
    }

    @PostMapping("/margin/daily-interest-rate")
    @Operation(summary = "修改杠杆日利率（decimal，例如0.0005=0.05%/天）")
    public Result<BigDecimal> setDailyInterestRate(@RequestBody DailyInterestRateRequest request) {
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
        assetSnapshotService.snapshotAll();
        return Result.ok();
    }

    @Data
    public static class DailyInterestRateRequest {
        private BigDecimal dailyInterestRate;
    }
}
