package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.service.ForceOrderService;
import com.mawai.wiibservice.service.FuturesRiskService;
import com.mawai.wiibservice.service.FuturesTradingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/futures")
@RequiredArgsConstructor
public class FuturesController {

    private final FuturesTradingService futuresTradingService;
    private final FuturesRiskService futuresRiskService;
    private final ForceOrderService forceOrderService;
    private final BinanceRestClient binanceRestClient;

    /** 开仓 */
    @PostMapping("/open")
    public Result<FuturesOrderResponse> open(@RequestBody FuturesOpenRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(futuresTradingService.openPosition(userId, request));
    }

    /** 平仓 */
    @PostMapping("/close")
    public Result<FuturesOrderResponse> close(@RequestBody FuturesCloseRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(futuresTradingService.closePosition(userId, request));
    }

    /** 取消限价单 */
    @PostMapping("/cancel/{orderId}")
    public Result<FuturesOrderResponse> cancel(@PathVariable Long orderId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(futuresTradingService.cancelOrder(userId, orderId));
    }

    /** 追加保证金 */
    @PostMapping("/margin")
    public Result<Void> addMargin(@RequestBody FuturesAddMarginRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        futuresTradingService.addMargin(userId, request);
        return Result.ok();
    }

    /** 加仓 */
    @PostMapping("/increase")
    public Result<FuturesOrderResponse> increase(@RequestBody FuturesIncreaseRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(futuresTradingService.increasePosition(userId, request));
    }

    /** 设置止损 */
    @PostMapping("/stop-loss")
    public Result<Void> setStopLoss(@RequestBody FuturesStopLossRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        futuresRiskService.setStopLoss(userId, request);
        return Result.ok();
    }

    /** 设置止盈 */
    @PostMapping("/take-profit")
    public Result<Void> setTakeProfit(@RequestBody FuturesTakeProfitRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        futuresRiskService.setTakeProfit(userId, request);
        return Result.ok();
    }

    /** 查询仓位列表 */
    @GetMapping("/positions")
    public Result<List<FuturesPositionDTO>> positions(@RequestParam(required = false) String symbol) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(futuresTradingService.getUserPositions(userId, symbol));
    }

    /** 查询订单列表 */
    @GetMapping("/orders")
    public Result<IPage<FuturesOrderResponse>> orders(@RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "10") int pageSize,
                                                       @RequestParam(required = false) String symbol) {
        Long userId = StpUtil.getLoginIdAsLong();
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

    @GetMapping("/klines")
    public String klines(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) Long endTime) {
        return binanceRestClient.getFuturesKlinesLight(symbol, interval, limit, endTime);
    }
}
