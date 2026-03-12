package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.service.FuturesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/futures")
@RequiredArgsConstructor
public class FuturesController {

    private final FuturesService futuresService;

    /** 开仓 */
    @PostMapping("/open")
    public Result<FuturesOrderResponse> open(@RequestBody FuturesOpenRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(futuresService.openPosition(userId, request));
    }

    /** 平仓 */
    @PostMapping("/close")
    public Result<FuturesOrderResponse> close(@RequestBody FuturesCloseRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(futuresService.closePosition(userId, request));
    }

    /** 取消限价单 */
    @PostMapping("/cancel/{orderId}")
    public Result<FuturesOrderResponse> cancel(@PathVariable Long orderId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(futuresService.cancelOrder(userId, orderId));
    }

    /** 追加保证金 */
    @PostMapping("/margin")
    public Result<Void> addMargin(@RequestBody FuturesAddMarginRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        futuresService.addMargin(userId, request);
        return Result.ok();
    }

    /** 加仓 */
    @PostMapping("/increase")
    public Result<FuturesOrderResponse> increase(@RequestBody FuturesIncreaseRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(futuresService.increasePosition(userId, request));
    }

    /** 设置止损 */
    @PostMapping("/stop-loss")
    public Result<Void> setStopLoss(@RequestBody FuturesStopLossRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        futuresService.setStopLoss(userId, request);
        return Result.ok();
    }

    @PostMapping("/take-profit")
    public Result<Void> setTakeProfit(@RequestBody FuturesTakeProfitRequest request) {
        Long userId = StpUtil.getLoginIdAsLong();
        futuresService.setTakeProfit(userId, request);
        return Result.ok();
    }

    /** 查询仓位列表 */
    @GetMapping("/positions")
    public Result<List<FuturesPositionDTO>> positions(@RequestParam(required = false) String symbol) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(futuresService.getUserPositions(userId, symbol));
    }

    /** 查询订单列表 */
    @GetMapping("/orders")
    public Result<IPage<FuturesOrderResponse>> orders(@RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "10") int pageSize,
                                                       @RequestParam(required = false) String symbol) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(futuresService.getUserOrders(userId, status, pageNum, pageSize, symbol));
    }

    /** 最新成交-匿名 */
    @GetMapping("/live")
    public Result<List<FuturesOrderResponse>> live() {
        return Result.ok(futuresService.getLatestOrders());
    }
}
