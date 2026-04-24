package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.service.PredictionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

@Tag(name = "BTC 5min涨跌预测")
@RestController
@RequestMapping("/api/prediction")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionService predictionService;

    @GetMapping("/current")
    @Operation(summary = "当前回合信息 + Polymarket实时价格")
    public Result<PredictionRoundResponse> current() {
        return Result.ok(predictionService.getCurrentRound());
    }

    @PostMapping("/buy")
    @Operation(summary = "买入合约 {side: UP/DOWN, amount: 金额}")
    public Result<PredictionBetResponse> buy(@RequestBody PredictionBuyRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(predictionService.buy(userId, request));
    }

    @PostMapping("/sell/{betId}")
    @Operation(summary = "卖出合约（按当前Polymarket价格）")
    public Result<PredictionBetResponse> sell(@PathVariable Long betId,
                                              @RequestParam(required = false) BigDecimal contracts) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(predictionService.sell(userId, betId, contracts));
    }

    @GetMapping("/bets")
    @Operation(summary = "我的下注历史")
    public Result<IPage<PredictionBetResponse>> bets(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(predictionService.getUserBets(userId, pageNum, pageSize));
    }

    @GetMapping("/rounds")
    @Operation(summary = "往期回合")
    public Result<IPage<PredictionRoundResponse>> rounds(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(predictionService.getSettledRounds(pageNum, pageSize));
    }

    @GetMapping("/pnl")
    @Operation(summary = "预测盈亏统计")
    public Result<PredictionPnlResponse> pnl() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(predictionService.getUserPnl(userId));
    }

    @GetMapping("/live")
    @Operation(summary = "最近20条全站下注记录")
    public Result<List<PredictionBetLiveResponse>> live() {
        return Result.ok(predictionService.getLiveActivity());
    }

    @GetMapping("/price-history")
    @Operation(summary = "最近5分钟BTC价格历史")
    public Result<List<Map<String, Object>>> priceHistory() {
        return Result.ok(predictionService.getPriceHistory());
    }
}
