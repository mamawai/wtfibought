package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.KlineCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/futures")
@RequiredArgsConstructor
public class FuturesController {

    private final FuturesTradingService futuresTradingService;
    private final FuturesRiskService futuresRiskService;
    private final ForceOrderService forceOrderService;
    private final KlineCacheService klineCacheService;
    private final FuturesLeverageBracketRegistry bracketRegistry;
    private final CrossMarginService crossMarginService;

    /** 开仓 */
    @PostMapping("/open")
    public Result<FuturesOrderResponse> open(@CurrentUserId Long userId, @RequestBody FuturesOpenRequest request) {
        return Result.ok(futuresTradingService.openPosition(userId, request));
    }

    /** 平仓 */
    @PostMapping("/close")
    public Result<FuturesOrderResponse> close(@CurrentUserId Long userId, @RequestBody FuturesCloseRequest request) {
        return Result.ok(futuresTradingService.closePosition(userId, request));
    }

    /** 一键全平：市价平掉当前用户全部仓位，返回成功笔数与失败明细 */
    @PostMapping("/close-all")
    public Result<FuturesTradingService.CloseAllResult> closeAll(@CurrentUserId Long userId) {
        return Result.ok(futuresTradingService.closeAllPositions(userId));
    }

    /** 取消限价单 */
    @PostMapping("/cancel/{orderId}")
    public Result<FuturesOrderResponse> cancel(@CurrentUserId Long userId, @PathVariable Long orderId) {
        return Result.ok(futuresTradingService.cancelOrder(userId, orderId));
    }

    /** 追加保证金 */
    @PostMapping("/margin")
    public Result<Void> addMargin(@CurrentUserId Long userId, @RequestBody FuturesAddMarginRequest request) {
        futuresTradingService.addMargin(userId, request);
        return Result.ok();
    }

    /** 减少逐仓保证金 */
    @PostMapping("/margin/reduce")
    public Result<Void> reduceMargin(@CurrentUserId Long userId, @RequestBody FuturesReduceMarginRequest request) {
        futuresTradingService.reduceMargin(userId, request);
        return Result.ok();
    }

    /** 持仓调杠杆：全仓双向可调，逐仓只能调高 */
    @PostMapping("/leverage")
    public Result<Void> adjustLeverage(@CurrentUserId Long userId, @RequestBody FuturesAdjustLeverageRequest request) {
        futuresTradingService.adjustLeverage(userId, request);
        return Result.ok();
    }

    /** 全仓账户概览：净值/可用/占用/维持保证金（开仓面板与仓位卡展示用） */
    @GetMapping("/cross-account")
    public Result<Map<String, Object>> crossAccount(@CurrentUserId Long userId) {
        var account = crossMarginService.snapshot(userId);
        return Result.ok(Map.of(
                "balance", account.balance(),
                "unrealizedPnl", account.unrealizedPnl(),
                "equity", account.equity(),
                "available", account.available(),
                "usedMargin", account.usedMargin(),
                "pendingReserved", account.pendingReserved(),
                "maintenanceMargin", account.maintenanceMargin(),
                "positionCount", account.positions().size()));
    }

    /** 设置止损 */
    @PostMapping("/stop-loss")
    public Result<Void> setStopLoss(@CurrentUserId Long userId, @RequestBody FuturesStopLossRequest request) {
        futuresRiskService.setStopLoss(userId, request);
        return Result.ok();
    }

    /** 设置止盈 */
    @PostMapping("/take-profit")
    public Result<Void> setTakeProfit(@CurrentUserId Long userId, @RequestBody FuturesTakeProfitRequest request) {
        futuresRiskService.setTakeProfit(userId, request);
        return Result.ok();
    }

    /** 查询仓位列表 */
    @GetMapping("/positions")
    public Result<List<FuturesPositionDTO>> positions(@CurrentUserId Long userId, @RequestParam(required = false) String symbol) {
        return Result.ok(futuresTradingService.getUserPositions(userId, symbol));
    }

    /** 查询订单列表 */
    @GetMapping("/orders")
    public Result<IPage<FuturesOrderResponse>> orders(@CurrentUserId Long userId,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "10") int pageSize,
                                                       @RequestParam(required = false) String symbol) {
        return Result.ok(futuresTradingService.getUserOrders(userId, status, pageNum, pageSize, symbol));
    }

    /** 最新成交-匿名 */
    @GetMapping("/live")
    public Result<List<FuturesOrderResponse>> live() {
        return Result.ok(futuresTradingService.getLatestOrders());
    }

    /** Binance爆仓记录-匿名 */
    @GetMapping("/force-orders")
    public Result<IPage<ForceOrder>> forceOrders(
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        return Result.ok(forceOrderService.getPage(symbol, safePageNum, safePageSize));
    }

    /** 合约K线（代理 Binance，Redis短TTL缓存） */
    @GetMapping("/klines")
    public String klines(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) Long endTime) {
        return klineCacheService.futuresKlines(symbol, interval, limit, endTime);
    }

    /** 永续合约档位表：前端预估强平价/MMR 用，避免与后端两份硬编码 */
    @GetMapping("/brackets")
    public Result<Map<String, List<FuturesLeverageBracketRegistry.Bracket>>> brackets() {
        return Result.ok(bracketRegistry.getAllBrackets());
    }
}
